/*
 * uvc_usbip - a virtual UVC webcam that speaks the USB/IP URB protocol.
 *
 * The Android emulator's kernel has vhci_hcd (the USB/IP virtual host controller) loaded.
 * Once a TCP socket is handed to it (see vhci_attach.c) the kernel sends every URB for the
 * attached "device" down that socket, so this program is the device: it answers the
 * enumeration and UVC class requests and serves a generated test pattern on the streaming
 * endpoint. Every control request is logged, which doubles as a wire trace of the app.
 *
 *   uvc_usbip [--port N] [--bulk] [--quiet] [--h264 FILE] [--only mjpg|yuy2|h264] [--audio]
 *             [--no-button]
 *   uvc_usbip --dump-jpeg out.jpg | --dump-yuyv out.yuv   (render one 640x480 frame and exit)
 *
 * Device: UVC 1.0, high speed, VID:PID 1209:0001
 *   MJPEG  640x480, 1280x720          (generated test pattern)
 *   YUY2   640x480, 320x240           (generated test pattern)
 *   H264   640x480, frame-based       (with --h264: access units from mp4_to_h264seq.py, looped)
 *   intervals 30 fps and 15 fps; processing unit with brightness/contrast/saturation
 *   video endpoint 0x81: isochronous (alt 1 = 1024, alt 2 = 3x1024) or bulk with --bulk
 *   --audio adds a USB Audio Class 1 microphone: 48 kHz 16-bit stereo on endpoint 0x82,
 *   mute/volume feature unit, sampling-frequency endpoint control; it plays a 440 Hz tone
 *   status interrupt endpoint 0x83 on the VideoControl interface, for the camera's button; the
 *   streaming header announces a hardware trigger for still capture (method 2; the still-image
 *   requests themselves are not implemented). --no-button leaves all of that out.
 *
 * The button is pushed from outside: creating /tmp/vcam-button sends "pressed" and then
 * "released"; /tmp/vcam-button-press and /tmp/vcam-button-release send only that one event.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <math.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

/* ---------- small helpers ---------- */

static int g_quiet;
static uint64_t g_t0;

static uint64_t now_us(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint64_t)ts.tv_sec * 1000000u + ts.tv_nsec / 1000;
}

static void logf_(const char *fmt, ...)
{
	va_list ap;
	uint64_t t = now_us() - g_t0;
	printf("[%4llu.%03llu] ", (unsigned long long)(t / 1000000), (unsigned long long)(t / 1000 % 1000));
	va_start(ap, fmt);
	vprintf(fmt, ap);
	va_end(ap);
	putchar('\n');
	fflush(stdout);
}

static uint32_t be32(const uint8_t *p) { return (uint32_t)p[0] << 24 | p[1] << 16 | p[2] << 8 | p[3]; }
static void wbe32(uint8_t *p, uint32_t v) { p[0] = v >> 24; p[1] = v >> 16; p[2] = v >> 8; p[3] = v; }
static uint16_t le16(const uint8_t *p) { return p[0] | p[1] << 8; }
static uint32_t le32(const uint8_t *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }
static void wle16(uint8_t *p, uint16_t v) { p[0] = v; p[1] = v >> 8; }
static void wle32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }

/* ---------- device model ---------- */

struct size { int w, h; };
enum { K_MJPG, K_YUY2, K_H264 };
struct format { int kind, nframes; struct size frames[2]; };
static struct format formats[3];
static int nformats;
static const uint32_t intervals[2] = { 333333, 666666 }; /* 100 ns units */
static const char *const kind_name[] = { "MJPG", "YUY2", "H264" };

#define CLOCK_HZ 48000000u
#define UFRAME_US 125

static struct {
	int bulk;
	int alt;            /* current alternate setting of the streaming interface */
	int committed;
	uint8_t probe[26], commit[26];
	int fmt, frame;
	uint32_t interval;
	uint16_t brightness, contrast, saturation;
	uint8_t last_error;

	uint8_t *bg, *yuyv, *jpeg;
	int bg_w, bg_h;
	const uint8_t *cur;
	size_t cur_len, cur_off;
	int frame_active, fid;
	uint32_t frame_no, pts;
	uint64_t next_frame_us;

	uint64_t stat_frames, stat_bytes, stat_urbs, stat_t;

	/* pre-encoded H.264 access units */
	uint8_t *h264;
	size_t h264_len, h264_pos;

	/* USB Audio Class 1 microphone */
	int audio, audio_alt, audio_mute;
	int16_t audio_volume;
	uint32_t audio_rate;
	double audio_phase;
	uint64_t stat_audio;

	/* button: status packets waiting for an interrupt transfer */
	int button, nstatus;
	uint8_t status[8];
	uint64_t status_t;
} S;

static int cur_kind(void) { return formats[S.fmt - 1].kind; }
static struct size cur_size(void) { return formats[S.fmt - 1].frames[S.frame - 1]; }

/* ---------- descriptors ---------- */

static uint8_t dev_desc[18] = {
	18, 1, 0x00, 0x02, 0xEF, 0x02, 0x01, 64, 0x09, 0x12, 0x01, 0x00, 0x00, 0x01, 1, 2, 3, 1
};
static const uint8_t qual_desc[10] = { 10, 6, 0x00, 0x02, 0xEF, 0x02, 0x01, 64, 1, 0 };
static uint8_t cfg_desc[1024];
static int cfg_len;

static uint8_t *d8(uint8_t *p, int v) { *p = v; return p + 1; }
static uint8_t *d16(uint8_t *p, int v) { wle16(p, v); return p + 2; }
static uint8_t *d32(uint8_t *p, uint32_t v) { wle32(p, v); return p + 4; }

static uint8_t *frame_desc(uint8_t *p, int subtype, int index, struct size s)
{
	uint32_t fsize = (uint32_t)s.w * s.h * 2;
	p = d8(p, 26 + 8); p = d8(p, 0x24); p = d8(p, subtype); p = d8(p, index); p = d8(p, 0);
	p = d16(p, s.w); p = d16(p, s.h);
	p = d32(p, fsize * 8 * 15); p = d32(p, fsize * 8 * 30);
	if (subtype == 0x11) { /* frame-based: default interval, type, bytes per line */
		p = d32(p, intervals[0]); p = d8(p, 2); p = d32(p, 0);
	} else {
		p = d32(p, fsize); p = d32(p, intervals[0]); p = d8(p, 2);
	}
	p = d32(p, intervals[0]); p = d32(p, intervals[1]);
	return p;
}

static uint8_t *guid(uint8_t *p, const char *fourcc)
{
	static const uint8_t tail[12] = { 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71 };
	memcpy(p, fourcc, 4);
	memcpy(p + 4, tail, 12);
	return p + 16;
}

static void build_config(void)
{
	uint8_t *p = cfg_desc, *vs_hdr, *vs_start;
	int i, f;

	/* configuration */
	p = d8(p, 9); p = d8(p, 2); p = d16(p, 0); p = d8(p, S.audio ? 4 : 2); p = d8(p, 1); p = d8(p, 0);
	p = d8(p, 0x80); p = d8(p, 250);
	/* interface association: video, 2 interfaces */
	p = d8(p, 8); p = d8(p, 0x0B); p = d8(p, 0); p = d8(p, 2); p = d8(p, 0x0E); p = d8(p, 0x03);
	p = d8(p, 0); p = d8(p, 2);
	/* VideoControl interface 0 */
	p = d8(p, 9); p = d8(p, 4); p = d8(p, 0); p = d8(p, 0); p = d8(p, S.button ? 1 : 0); p = d8(p, 0x0E);
	p = d8(p, 0x01); p = d8(p, 0); p = d8(p, 0);
	/* VC header (UVC 1.0), total = 13 + 17 + 11 + 9 */
	p = d8(p, 13); p = d8(p, 0x24); p = d8(p, 0x01); p = d16(p, 0x0100); p = d16(p, 50);
	p = d32(p, CLOCK_HZ); p = d8(p, 1); p = d8(p, 1);
	/* camera input terminal, id 1 */
	p = d8(p, 17); p = d8(p, 0x24); p = d8(p, 0x02); p = d8(p, 1); p = d16(p, 0x0201);
	p = d8(p, 0); p = d8(p, 0); p = d16(p, 0); p = d16(p, 0); p = d16(p, 0);
	p = d8(p, 2); p = d16(p, 0x0000);
	/* processing unit, id 2: brightness, contrast, saturation */
	p = d8(p, 11); p = d8(p, 0x24); p = d8(p, 0x05); p = d8(p, 2); p = d8(p, 1); p = d16(p, 0);
	p = d8(p, 2); p = d16(p, 0x000B); p = d8(p, 0);
	/* output terminal, id 3: USB streaming */
	p = d8(p, 9); p = d8(p, 0x24); p = d8(p, 0x03); p = d8(p, 3); p = d16(p, 0x0101);
	p = d8(p, 0); p = d8(p, 2); p = d8(p, 0);
	if (S.button) {
		/* status endpoint 0x83: interrupt, 10 bytes, and its class descriptor (64-byte transfers) */
		p = d8(p, 7); p = d8(p, 5); p = d8(p, 0x83); p = d8(p, 0x03); p = d16(p, 10); p = d8(p, 5);
		p = d8(p, 5); p = d8(p, 0x25); p = d8(p, 0x03); p = d16(p, 64);
	}

	/* VideoStreaming interface 1, alt 0 */
	p = d8(p, 9); p = d8(p, 4); p = d8(p, 1); p = d8(p, 0); p = d8(p, S.bulk ? 1 : 0);
	p = d8(p, 0x0E); p = d8(p, 0x02); p = d8(p, 0); p = d8(p, 0);
	vs_start = p;
	/* VS input header */
	vs_hdr = p;
	p = d8(p, 13 + nformats); p = d8(p, 0x24); p = d8(p, 0x01); p = d8(p, nformats); p = d16(p, 0);
	p = d8(p, 0x81); p = d8(p, 0); p = d8(p, 3); p = d8(p, S.button ? 2 : 0); p = d8(p, S.button ? 1 : 0);
	p = d8(p, 0); p = d8(p, 1);
	for (f = 0; f < nformats; f++)
		p = d8(p, 0);
	for (f = 0; f < nformats; f++) {
		const struct format *fm = &formats[f];
		int frame_subtype;

		if (fm->kind == K_MJPG) {
			p = d8(p, 11); p = d8(p, 0x24); p = d8(p, 0x06); p = d8(p, f + 1); p = d8(p, fm->nframes);
			p = d8(p, 1); p = d8(p, 1); p = d8(p, 0); p = d8(p, 0); p = d8(p, 0); p = d8(p, 0);
			frame_subtype = 0x07;
		} else if (fm->kind == K_YUY2) {
			p = d8(p, 27); p = d8(p, 0x24); p = d8(p, 0x04); p = d8(p, f + 1); p = d8(p, fm->nframes);
			p = guid(p, "YUY2");
			p = d8(p, 16); p = d8(p, 1); p = d8(p, 0); p = d8(p, 0); p = d8(p, 0); p = d8(p, 0);
			frame_subtype = 0x05;
		} else { /* frame-based H.264 */
			p = d8(p, 28); p = d8(p, 0x24); p = d8(p, 0x10); p = d8(p, f + 1); p = d8(p, fm->nframes);
			p = guid(p, "H264");
			p = d8(p, 16); p = d8(p, 1); p = d8(p, 0); p = d8(p, 0); p = d8(p, 0); p = d8(p, 0); p = d8(p, 1);
			frame_subtype = 0x11;
		}
		for (i = 0; i < fm->nframes; i++)
			p = frame_desc(p, frame_subtype, i + 1, fm->frames[i]);
		if (S.button && fm->kind != K_H264) { /* still image frame: the sizes of the video frames */
			p = d8(p, 6 + 4 * fm->nframes); p = d8(p, 0x24); p = d8(p, 0x03); p = d8(p, 0); p = d8(p, fm->nframes);
			for (i = 0; i < fm->nframes; i++) {
				p = d16(p, fm->frames[i].w); p = d16(p, fm->frames[i].h);
			}
			p = d8(p, 0);
		}
		p = d8(p, 6); p = d8(p, 0x24); p = d8(p, 0x0D); p = d8(p, 1); p = d8(p, 1); p = d8(p, 4);
	}
	wle16(vs_hdr + 4, p - vs_start);

	if (S.bulk) {
		p = d8(p, 7); p = d8(p, 5); p = d8(p, 0x81); p = d8(p, 0x02); p = d16(p, 512); p = d8(p, 0);
	} else {
		/* alt 1: 1024 bytes per microframe */
		p = d8(p, 9); p = d8(p, 4); p = d8(p, 1); p = d8(p, 1); p = d8(p, 1); p = d8(p, 0x0E);
		p = d8(p, 0x02); p = d8(p, 0); p = d8(p, 0);
		p = d8(p, 7); p = d8(p, 5); p = d8(p, 0x81); p = d8(p, 0x05); p = d16(p, 0x0400); p = d8(p, 1);
		/* alt 2: 3 x 1024 bytes per microframe */
		p = d8(p, 9); p = d8(p, 4); p = d8(p, 1); p = d8(p, 2); p = d8(p, 1); p = d8(p, 0x0E);
		p = d8(p, 0x02); p = d8(p, 0); p = d8(p, 0);
		p = d8(p, 7); p = d8(p, 5); p = d8(p, 0x81); p = d8(p, 0x05); p = d16(p, 0x1400); p = d8(p, 1);
	}
	if (S.audio) {
		/* AudioControl interface 2: microphone (1) -> feature unit (2) -> USB streaming (3) */
		p = d8(p, 9); p = d8(p, 4); p = d8(p, 2); p = d8(p, 0); p = d8(p, 0); p = d8(p, 0x01);
		p = d8(p, 0x01); p = d8(p, 0); p = d8(p, 0);
		p = d8(p, 9); p = d8(p, 0x24); p = d8(p, 0x01); p = d16(p, 0x0100); p = d16(p, 40);
		p = d8(p, 1); p = d8(p, 3);
		p = d8(p, 12); p = d8(p, 0x24); p = d8(p, 0x02); p = d8(p, 1); p = d16(p, 0x0201);
		p = d8(p, 0); p = d8(p, 2); p = d16(p, 0x0003); p = d8(p, 0); p = d8(p, 0);
		p = d8(p, 10); p = d8(p, 0x24); p = d8(p, 0x06); p = d8(p, 2); p = d8(p, 1); p = d8(p, 1);
		p = d8(p, 0x03); p = d8(p, 0); p = d8(p, 0); p = d8(p, 0);
		p = d8(p, 9); p = d8(p, 0x24); p = d8(p, 0x03); p = d8(p, 3); p = d16(p, 0x0101);
		p = d8(p, 0); p = d8(p, 2); p = d8(p, 0);
		/* AudioStreaming interface 3, alt 0 (idle) and alt 1 (48 kHz 16-bit stereo PCM) */
		p = d8(p, 9); p = d8(p, 4); p = d8(p, 3); p = d8(p, 0); p = d8(p, 0); p = d8(p, 0x01);
		p = d8(p, 0x02); p = d8(p, 0); p = d8(p, 0);
		p = d8(p, 9); p = d8(p, 4); p = d8(p, 3); p = d8(p, 1); p = d8(p, 1); p = d8(p, 0x01);
		p = d8(p, 0x02); p = d8(p, 0); p = d8(p, 0);
		p = d8(p, 7); p = d8(p, 0x24); p = d8(p, 0x01); p = d8(p, 3); p = d8(p, 1); p = d16(p, 0x0001);
		p = d8(p, 11); p = d8(p, 0x24); p = d8(p, 0x02); p = d8(p, 1); p = d8(p, 2); p = d8(p, 2);
		p = d8(p, 16); p = d8(p, 1); p = d8(p, 0x80); p = d8(p, 0xBB); p = d8(p, 0x00);
		p = d8(p, 9); p = d8(p, 5); p = d8(p, 0x82); p = d8(p, 0x05); p = d16(p, 200); p = d8(p, 4);
		p = d8(p, 0); p = d8(p, 0);
		p = d8(p, 7); p = d8(p, 0x25); p = d8(p, 0x01); p = d8(p, 0x01); p = d8(p, 0); p = d16(p, 0);
	}
	cfg_len = p - cfg_desc;
	wle16(cfg_desc + 2, cfg_len);
}

static int string_desc(int idx, uint8_t *out)
{
	static const char *strs[] = { NULL, "HardLine", "Virtual UVC Test Camera", "VCAM0001" };
	const char *s;
	int i, n;

	if (idx == 0) {
		out[0] = 4; out[1] = 3; out[2] = 0x09; out[3] = 0x04;
		return 4;
	}
	if (idx > 3)
		return -1;
	s = strs[idx];
	n = strlen(s);
	out[0] = 2 + 2 * n; out[1] = 3;
	for (i = 0; i < n; i++) {
		out[2 + 2 * i] = s[i];
		out[3 + 2 * i] = 0;
	}
	return 2 + 2 * n;
}

/* ---------- test pattern ---------- */

static const uint8_t bars[8][3] = { /* 75% colour bars, BT.601 Y Cb Cr */
	{ 180, 128, 128 }, { 162, 44, 142 }, { 131, 156, 44 }, { 112, 72, 58 },
	{ 84, 184, 198 }, { 65, 100, 212 }, { 35, 212, 114 }, { 16, 128, 128 }
};
static const uint8_t font3x5[10][5] = {
	{ 7, 5, 5, 5, 7 }, { 2, 6, 2, 2, 7 }, { 7, 1, 7, 4, 7 }, { 7, 1, 7, 1, 7 }, { 5, 5, 7, 1, 1 },
	{ 7, 4, 7, 1, 7 }, { 7, 4, 7, 5, 7 }, { 7, 1, 1, 1, 1 }, { 7, 5, 7, 5, 7 }, { 7, 5, 7, 1, 7 }
};

static void fill_rect(uint8_t *f, int w, int h, int x0, int y0, int rw, int rh, int y, int u, int v)
{
	int x, yy;
	x0 &= ~1;
	for (yy = y0; yy < y0 + rh && yy < h; yy++)
		for (x = x0; x + 1 < x0 + rw && x + 1 < w; x += 2) {
			uint8_t *p = f + ((size_t)yy * w + x) * 2;
			p[0] = y; p[1] = u; p[2] = y; p[3] = v;
		}
}

static void render_yuyv(int w, int h)
{
	size_t len = (size_t)w * h * 2, i;
	int x, y, scale = w / 160 ? w / 160 : 1, digits[8], n, d;
	/* While /tmp/vcam-still exists the picture stops changing (to test motion-detection time-outs). */
	static uint32_t frozen;
	if (access("/tmp/vcam-still", F_OK) != 0) frozen = S.frame_no;
	uint32_t v = frozen;

	if (S.bg_w != w || S.bg_h != h) {
		for (y = 0; y < h; y++)
			for (x = 0; x < w; x += 2) {
				const uint8_t *c = bars[x * 8 / w];
				uint8_t *p = S.bg + ((size_t)y * w + x) * 2;
				/* lower quarter: luma ramp, so brightness/contrast changes are visible */
				int yy = y >= h * 3 / 4 ? 16 + x * 219 / w : c[0];
				p[0] = yy; p[1] = y >= h * 3 / 4 ? 128 : c[1];
				p[2] = yy; p[3] = y >= h * 3 / 4 ? 128 : c[2];
			}
		S.bg_w = w; S.bg_h = h;
	}
	memcpy(S.yuyv, S.bg, len);

	/* moving white box */
	fill_rect(S.yuyv, w, h, (frozen * 8) % (w - w / 10), h / 2 - h / 16, w / 10, h / 8, 235, 128, 128);
	/* frame counter, 8 digits on a black strip */
	fill_rect(S.yuyv, w, h, 8 * scale, 4 * scale, 34 * scale, 7 * scale, 16, 128, 128);
	for (n = 7; n >= 0; n--, v /= 10)
		digits[n] = v % 10;
	for (n = 0; n < 8; n++)
		for (y = 0; y < 5; y++)
			for (d = 0; d < 3; d++)
				if (font3x5[digits[n]][y] & (4 >> d))
					fill_rect(S.yuyv, w, h, (9 + n * 4 + d) * scale, (5 + y) * scale,
						  scale < 2 ? 2 : scale, scale, 235, 128, 128);

	if (S.brightness != 128 || S.contrast != 128) {
		uint8_t lut[256];
		for (i = 0; i < 256; i++) {
			int t = ((int)i - 128) * S.contrast / 128 + 128 + (S.brightness - 128);
			lut[i] = t < 0 ? 0 : t > 255 ? 255 : t;
		}
		for (i = 0; i < len; i += 2)
			S.yuyv[i] = lut[S.yuyv[i]];
	}
	if (S.saturation != 128)
		for (i = 1; i < len; i += 2) {
			int t = ((int)S.yuyv[i] - 128) * S.saturation / 128 + 128;
			S.yuyv[i] = t < 0 ? 0 : t > 255 ? 255 : t;
		}
}

/* ---------- baseline JPEG encoder (4:2:2, straight from YUYV) ---------- */

static const uint8_t zz[64] = {
	0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20,
	13, 6, 7, 14, 21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59,
	52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
};
static const uint8_t qbase[2][64] = { {
	16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
	14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
	49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99 }, {
	17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99,
	47, 66, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
	99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99 } };
static const uint8_t dc_bits[2][16] = {
	{ 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0 }, { 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0 } };
static const uint8_t dc_vals[12] = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 };
static const uint8_t ac_bits[2][16] = {
	{ 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d }, { 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77 } };
static const uint8_t ac_vals[2][162] = { {
	0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
	0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
	0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
	0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
	0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
	0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
	0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
	0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
	0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
	0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
	0xf9, 0xfa }, {
	0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
	0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
	0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
	0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
	0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
	0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
	0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
	0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
	0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
	0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
	0xf9, 0xfa } };

static uint8_t qt[2][64];
static uint16_t hcode[4][256];
static uint8_t hsize[4][256];
static float dct_c[8][8];

static void build_huff(int t, const uint8_t *bits, const uint8_t *vals)
{
	int len, i, k = 0, code = 0;
	for (len = 1; len <= 16; len++) {
		for (i = 0; i < bits[len - 1]; i++, k++, code++) {
			hcode[t][vals[k]] = code;
			hsize[t][vals[k]] = len;
		}
		code <<= 1;
	}
}

static void jpeg_init(int quality)
{
	int t, i, u, x, scale = quality < 50 ? 5000 / quality : 200 - 2 * quality;
	for (t = 0; t < 2; t++)
		for (i = 0; i < 64; i++) {
			int v = (qbase[t][i] * scale + 50) / 100;
			qt[t][i] = v < 1 ? 1 : v > 255 ? 255 : v;
		}
	build_huff(0, dc_bits[0], dc_vals);
	build_huff(1, ac_bits[0], ac_vals[0]);
	build_huff(2, dc_bits[1], dc_vals);
	build_huff(3, ac_bits[1], ac_vals[1]);
	for (u = 0; u < 8; u++)
		for (x = 0; x < 8; x++)
			dct_c[u][x] = 0.5f * (u ? 1.0f : 0.70710678f) * cosf((2 * x + 1) * u * (float)M_PI / 16);
}

struct bitw { uint8_t *p; uint32_t acc; int n; };

static void put_bits(struct bitw *b, uint32_t code, int size)
{
	b->acc = b->acc << size | (code & ((1u << size) - 1));
	b->n += size;
	while (b->n >= 8) {
		uint8_t c = b->acc >> (b->n - 8);
		*b->p++ = c;
		if (c == 0xFF)
			*b->p++ = 0;
		b->n -= 8;
	}
}

static int bit_len(int v)
{
	int n = 0;
	if (v < 0)
		v = -v;
	while (v) {
		n++;
		v >>= 1;
	}
	return n;
}

static void encode_block(struct bitw *b, const float *in, int comp, int *last_dc)
{
	float tmp[64], out;
	int q[64], y, x, u, v, k, run = 0, cat, diff;
	const uint8_t *qtab = qt[comp ? 1 : 0];
	int dct = comp ? 2 : 0, act = comp ? 3 : 1;

	for (y = 0; y < 8; y++)
		for (u = 0; u < 8; u++) {
			float s = 0;
			for (x = 0; x < 8; x++)
				s += in[y * 8 + x] * dct_c[u][x];
			tmp[y * 8 + u] = s;
		}
	for (k = 0; k < 64; k++) {
		int nat = zz[k];
		u = nat & 7; v = nat >> 3;
		out = 0;
		for (y = 0; y < 8; y++)
			out += tmp[y * 8 + u] * dct_c[v][y];
		q[k] = (int)lrintf(out / qtab[nat]);
	}

	diff = q[0] - *last_dc;
	*last_dc = q[0];
	cat = bit_len(diff);
	put_bits(b, hcode[dct][cat], hsize[dct][cat]);
	if (cat)
		put_bits(b, diff < 0 ? diff - 1 : diff, cat);
	for (k = 1; k < 64; k++) {
		if (!q[k]) {
			run++;
			continue;
		}
		while (run > 15) {
			put_bits(b, hcode[act][0xF0], hsize[act][0xF0]);
			run -= 16;
		}
		cat = bit_len(q[k]);
		put_bits(b, hcode[act][run << 4 | cat], hsize[act][run << 4 | cat]);
		put_bits(b, q[k] < 0 ? q[k] - 1 : q[k], cat);
		run = 0;
	}
	if (run)
		put_bits(b, hcode[act][0], hsize[act][0]);
}

static uint8_t *put_dht(uint8_t *p, int id, const uint8_t *bits, const uint8_t *vals, int n)
{
	*p++ = 0xFF; *p++ = 0xC4; *p++ = 0; *p++ = 3 + 16 + n; *p++ = id;
	memcpy(p, bits, 16); p += 16;
	memcpy(p, vals, n);
	return p + n;
}

static size_t jpeg_encode_yuyv(const uint8_t *src, int w, int h, uint8_t *dst)
{
	static const uint8_t app0[] = { 0xFF, 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0 };
	static const uint8_t sos[] = { 0xFF, 0xDA, 0, 12, 3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0 };
	struct bitw b;
	uint8_t *p = dst;
	float blk[4][64];
	int t, i, mx, my, x, y, dc[3] = { 0, 0, 0 };

	*p++ = 0xFF; *p++ = 0xD8;
	memcpy(p, app0, sizeof app0); p += sizeof app0;
	for (t = 0; t < 2; t++) {
		*p++ = 0xFF; *p++ = 0xDB; *p++ = 0; *p++ = 67; *p++ = t;
		for (i = 0; i < 64; i++)
			*p++ = qt[t][zz[i]];
	}
	*p++ = 0xFF; *p++ = 0xC0; *p++ = 0; *p++ = 17; *p++ = 8;
	*p++ = h >> 8; *p++ = h; *p++ = w >> 8; *p++ = w; *p++ = 3;
	*p++ = 1; *p++ = 0x21; *p++ = 0;
	*p++ = 2; *p++ = 0x11; *p++ = 1;
	*p++ = 3; *p++ = 0x11; *p++ = 1;
	p = put_dht(p, 0x00, dc_bits[0], dc_vals, 12);
	p = put_dht(p, 0x10, ac_bits[0], ac_vals[0], 162);
	p = put_dht(p, 0x01, dc_bits[1], dc_vals, 12);
	p = put_dht(p, 0x11, ac_bits[1], ac_vals[1], 162);
	memcpy(p, sos, sizeof sos); p += sizeof sos;

	b.p = p; b.acc = 0; b.n = 0;
	for (my = 0; my < h; my += 8)
		for (mx = 0; mx < w; mx += 16) {
			for (y = 0; y < 8; y++) {
				const uint8_t *row = src + ((size_t)(my + y) * w + mx) * 2;
				for (x = 0; x < 8; x++) {
					blk[0][y * 8 + x] = row[x * 2] - 128.0f;
					blk[1][y * 8 + x] = row[16 + x * 2] - 128.0f;
					blk[2][y * 8 + x] = row[x * 4 + 1] - 128.0f;
					blk[3][y * 8 + x] = row[x * 4 + 3] - 128.0f;
				}
			}
			encode_block(&b, blk[0], 0, &dc[0]);
			encode_block(&b, blk[1], 0, &dc[0]);
			encode_block(&b, blk[2], 1, &dc[1]);
			encode_block(&b, blk[3], 2, &dc[2]);
		}
	if (b.n)
		put_bits(&b, 0x7F, 8 - b.n);
	p = b.p;
	*p++ = 0xFF; *p++ = 0xD9;
	return p - dst;
}

/* ---------- streaming ---------- */

static void stream_reset(const char *why)
{
	S.frame_active = 0;
	S.fid = 0;
	S.next_frame_us = now_us();
	if (!g_quiet)
		logf_("STREAM %s (fmt=%s %dx%d interval=%u alt=%d)", why, kind_name[cur_kind()],
		      cur_size().w, cur_size().h, S.interval, S.alt);
	S.h264_pos = 0; /* always restart the clip on its first keyframe */
}

static int streaming_on(void)
{
	return S.committed && (S.bulk || S.alt != 0);
}

static void start_frame(uint64_t t)
{
	struct size s = cur_size();

	if (cur_kind() == K_H264) {
		if (S.h264_pos + 4 > S.h264_len)
			S.h264_pos = 0;
		S.cur_len = be32(S.h264 + S.h264_pos);
		S.cur = S.h264 + S.h264_pos + 4;
		S.h264_pos += 4 + S.cur_len;
	} else {
		render_yuyv(s.w, s.h);
		if (cur_kind() == K_YUY2) {
			S.cur = S.yuyv;
			S.cur_len = (size_t)s.w * s.h * 2;
		} else {
			S.cur = S.jpeg;
			S.cur_len = jpeg_encode_yuyv(S.yuyv, s.w, s.h, S.jpeg);
		}
	}
	S.cur_off = 0;
	S.frame_active = 1;
	S.pts = (uint32_t)(t * (CLOCK_HZ / 1000000));
	S.frame_no++;
	S.stat_frames++;
	S.next_frame_us += S.interval / 10;
	if (S.next_frame_us + S.interval / 10 < t)
		S.next_frame_us = t + S.interval / 10;
}

/* Produce one UVC payload (12-byte header + data) of at most max bytes; 0 if nothing is due. */
static int next_payload(uint8_t *dst, int max, uint64_t t)
{
	size_t n;

	if (!streaming_on() || max <= 12)
		return 0;
	if (!S.frame_active) {
		if (t < S.next_frame_us)
			return 0;
		start_frame(t);
	}
	n = S.cur_len - S.cur_off;
	if (n > (size_t)max - 12)
		n = max - 12;
	dst[0] = 12;
	dst[1] = 0x80 | 0x08 | 0x04 | S.fid; /* EOH, SCR, PTS */
	wle32(dst + 2, S.pts);
	wle32(dst + 6, (uint32_t)(t * (CLOCK_HZ / 1000000)));
	wle16(dst + 10, (t / 1000) & 0x7FF);
	memcpy(dst + 12, S.cur + S.cur_off, n);
	S.cur_off += n;
	if (S.cur_off == S.cur_len) {
		dst[1] |= 0x02; /* EOF */
		S.frame_active = 0;
		S.fid ^= 1;
	}
	S.stat_bytes += n;
	return 12 + n;
}

/* One isochronous audio packet: 16-bit stereo PCM, 440 Hz tone. */
static int next_audio(uint8_t *dst, int max, int period_us)
{
	int frames = (int)((uint64_t)S.audio_rate * period_us / 1000000), i;

	if (!S.audio_alt)
		return 0;
	if (frames * 4 > max)
		frames = max / 4;
	for (i = 0; i < frames; i++) {
		int16_t v = S.audio_mute ? 0 : (int16_t)(8000 * sin(S.audio_phase));
		S.audio_phase += 2 * M_PI * 440 / S.audio_rate;
		wle16(dst + i * 4, (uint16_t)v);
		wle16(dst + i * 4 + 2, (uint16_t)v);
	}
	if (S.audio_phase > 2 * M_PI * 1000)
		S.audio_phase = fmod(S.audio_phase, 2 * M_PI);
	S.stat_audio += frames;
	return frames * 4;
}

/* ---------- USB/IP plumbing ---------- */

#define CMD_SUBMIT 1
#define CMD_UNLINK 2
#define RET_SUBMIT 3
#define RET_UNLINK 4
#define MAX_PENDING 1024
#define MAX_ISO_PKTS 256

struct pending {
	uint32_t seq;
	int ep;              /* 1 = video, 2 = audio, 3 = status */
	int npkts;           /* 0 for bulk */
	int period;          /* microseconds per isochronous packet */
	uint32_t len;        /* bulk: requested length */
	uint64_t start, due;
	uint32_t pkt_off[MAX_ISO_PKTS], pkt_len[MAX_ISO_PKTS];
};

static struct pending *pend[MAX_PENDING];
static int npend;
static uint64_t last_due[3];
static int sock;
static uint8_t *txbuf;

static int send_all(const void *buf, size_t len)
{
	const uint8_t *p = buf;
	while (len) {
		ssize_t n = send(sock, p, len, MSG_NOSIGNAL);
		if (n <= 0) {
			if (n < 0 && errno == EINTR)
				continue;
			return -1;
		}
		p += n;
		len -= n;
	}
	return 0;
}

static int send_ret_submit(uint32_t seq, int32_t status, const uint8_t *data, uint32_t len,
			   int npkts, const uint8_t *iso, int start_frame)
{
	uint8_t h[48];
	memset(h, 0, sizeof h);
	wbe32(h, RET_SUBMIT);
	wbe32(h + 4, seq);
	wbe32(h + 20, (uint32_t)status);
	wbe32(h + 24, len);
	wbe32(h + 28, start_frame);
	wbe32(h + 32, npkts);
	if (send_all(h, 48) || (len && data && send_all(data, len)) || (npkts && send_all(iso, npkts * 16)))
		return -1;
	return 0;
}

static int send_ret_unlink(uint32_t seq, int32_t status)
{
	uint8_t h[48];
	memset(h, 0, sizeof h);
	wbe32(h, RET_UNLINK);
	wbe32(h + 4, seq);
	wbe32(h + 20, (uint32_t)status);
	return send_all(h, 48);
}

static const char *uvc_req(int r)
{
	switch (r) {
	case 0x01: return "SET_CUR";
	case 0x81: return "GET_CUR";
	case 0x82: return "GET_MIN";
	case 0x83: return "GET_MAX";
	case 0x84: return "GET_RES";
	case 0x85: return "GET_LEN";
	case 0x86: return "GET_INFO";
	case 0x87: return "GET_DEF";
	}
	return "?";
}

static void negotiate(const uint8_t *in, uint8_t *out)
{
	int fmt = in[2], frame = in[3];
	uint32_t iv = le32(in + 4), payload, fsize;
	const struct format *fm;
	struct size s;

	if (fmt < 1 || fmt > nformats)
		fmt = 1;
	fm = &formats[fmt - 1];
	if (frame < 1 || frame > fm->nframes)
		frame = 1;
	iv = iv > (intervals[0] + intervals[1]) / 2 ? intervals[1] : intervals[0];
	s = fm->frames[frame - 1];
	fsize = (uint32_t)s.w * s.h * 2;
	if (S.bulk)
		payload = 16384;
	else if (fm->kind == K_YUY2)
		payload = (uint64_t)fsize * 10000000 / iv / 8000 + 12 <= 1024 ? 1024 : 3072;
	else
		payload = s.w > 640 ? 3072 : 1024;

	memset(out, 0, 26);
	wle16(out, 1);
	out[2] = fmt; out[3] = frame;
	wle32(out + 4, iv);
	wle32(out + 18, fsize);
	wle32(out + 22, payload);
}

/* Returns reply length (>= 0) or -1 to stall. */
static int vs_control(int req, int cs, const uint8_t *data, int dlen, uint8_t *out)
{
	uint8_t *cur = cs == 1 ? S.probe : S.commit;

	if (cs != 1 && cs != 2)
		return -1;
	switch (req) {
	case 0x01:
		if (dlen < 26)
			return -1;
		negotiate(data, S.probe);
		if (cs == 2) {
			memcpy(S.commit, S.probe, 26);
			S.fmt = S.commit[2];
			S.frame = S.commit[3];
			S.interval = le32(S.commit + 4);
			S.committed = 1;
			stream_reset("COMMIT");
		}
		return 0;
	case 0x81:
		memcpy(out, cur, 26);
		return 26;
	case 0x82: case 0x83: case 0x87: {
		uint8_t req_[26];
		memcpy(req_, cur, 26);
		if (req == 0x87) {
			req_[2] = 1; req_[3] = 1;
		}
		wle32(req_ + 4, req == 0x83 ? intervals[1] : intervals[0]);
		negotiate(req_, out);
		return 26;
	}
	case 0x84:
		memset(out, 0, 26);
		return 26;
	case 0x85:
		wle16(out, 26);
		return 2;
	case 0x86:
		out[0] = 3;
		return 1;
	}
	return -1;
}

static int pu_control(int req, int cs, const uint8_t *data, int dlen, uint8_t *out)
{
	uint16_t *v = cs == 2 ? &S.brightness : cs == 3 ? &S.contrast : cs == 7 ? &S.saturation : NULL;

	if (!v)
		return -1;
	switch (req) {
	case 0x01:
		if (dlen < 2)
			return -1;
		*v = le16(data) > 255 ? 255 : le16(data);
		return 0;
	case 0x81: wle16(out, *v); return 2;
	case 0x82: wle16(out, 0); return 2;
	case 0x83: wle16(out, 255); return 2;
	case 0x84: wle16(out, 1); return 2;
	case 0x85: wle16(out, 2); return 2;
	case 0x86: out[0] = 3; return 1;
	case 0x87: wle16(out, 128); return 2;
	}
	return -1;
}

/* USB Audio Class 1: feature unit (entity 2) mute and volume. */
static int audio_unit_control(int req, int cs, const uint8_t *data, int dlen, uint8_t *out)
{
	if (cs == 1) { /* mute */
		if (req == 0x01 && dlen >= 1) { S.audio_mute = data[0] & 1; return 0; }
		if (req == 0x81) { out[0] = S.audio_mute; return 1; }
		return -1;
	}
	if (cs != 2)
		return -1;
	switch (req) { /* volume, 1/256 dB */
	case 0x01:
		if (dlen < 2)
			return -1;
		S.audio_volume = (int16_t)le16(data);
		return 0;
	case 0x81: wle16(out, (uint16_t)S.audio_volume); return 2;
	case 0x82: wle16(out, 0xDB00); return 2;
	case 0x83: wle16(out, 0x0000); return 2;
	case 0x84: wle16(out, 0x0100); return 2;
	}
	return -1;
}

/* USB Audio Class 1: sampling-frequency control on the streaming endpoint. */
static int audio_ep_control(int req, int cs, const uint8_t *data, int dlen, uint8_t *out)
{
	if (cs != 1)
		return -1;
	if (req == 0x01) {
		if (dlen < 3)
			return -1;
		S.audio_rate = data[0] | data[1] << 8 | data[2] << 16;
		return 0;
	}
	if (req == 0x81 || req == 0x82 || req == 0x83) {
		uint32_t r = req == 0x81 ? S.audio_rate : 48000;
		out[0] = r; out[1] = r >> 8; out[2] = r >> 16;
		return 3;
	}
	return -1;
}

static int handle_control(const uint8_t *setup, const uint8_t *data, int dlen, uint8_t *out)
{
	int bm = setup[0], req = setup[1], wValue = le16(setup + 2), wIndex = le16(setup + 4);
	int wLength = le16(setup + 6), type = bm >> 5 & 3, recip = bm & 0x1F, n = -1;

	if (type == 0) {
		switch (req) {
		case 0: /* GET_STATUS */
			wle16(out, 0); n = 2; break;
		case 1: case 3: /* CLEAR_FEATURE / SET_FEATURE */
			if (recip == 2 && S.bulk && S.committed) {
				S.committed = 0;
				stream_reset("stopped (clear halt)");
			}
			n = 0; break;
		case 5: n = 0; break;
		case 6:
			switch (wValue >> 8) {
			case 1: memcpy(out, dev_desc, n = 18); break;
			case 2: memcpy(out, cfg_desc, n = cfg_len); break;
			case 3: n = string_desc(wValue & 0xFF, out); break;
			case 6: memcpy(out, qual_desc, n = 10); break;
			}
			break;
		case 8: out[0] = 1; n = 1; break;
		case 9: n = 0; break;
		case 10: out[0] = wIndex == 1 ? S.alt : wIndex == 3 ? S.audio_alt : 0; n = 1; break;
		case 11:
			if (wIndex == 1 && wValue <= (S.bulk ? 0 : 2)) {
				S.alt = wValue;
				stream_reset(wValue ? "interface alt set" : "stopped (alt 0)");
				n = 0;
			} else if (S.audio && wIndex == 3 && wValue <= 1) {
				S.audio_alt = wValue;
				if (!g_quiet)
					logf_("AUDIO %s (%u Hz)", wValue ? "streaming" : "stopped", S.audio_rate);
				n = 0;
			} else if ((wIndex == 0 || wIndex == 2) && wValue == 0) {
				n = 0;
			}
			break;
		}
		if (!g_quiet && req != 6)
			logf_("CTRL std  req=%d wValue=0x%04x wIndex=%d -> %s", req, wValue, wIndex, n < 0 ? "STALL" : "ok");
	} else if (type == 1 && S.audio && (recip == 2 || (recip == 1 && (wIndex & 0xFF) == 2))) {
		int cs = wValue >> 8;
		char detail[64] = "";

		n = recip == 2 ? audio_ep_control(req, cs, data, dlen, out)
			       : (wIndex >> 8) == 2 ? audio_unit_control(req, cs, data, dlen, out) : -1;
		if (recip == 2 && n >= 0)
			snprintf(detail, sizeof detail, " rate=%u", S.audio_rate);
		else if (req == 0x01 && n == 0)
			snprintf(detail, sizeof detail, " <- %d (channel %d)", cs == 1 ? S.audio_mute : S.audio_volume, wValue & 0xFF);
		else if (n == 2)
			snprintf(detail, sizeof detail, " = %d", (int16_t)le16(out));
		if (!g_quiet)
			logf_("CTRL uac  %-9s %-22s index=0x%04x cs=%d len=%d -> %s%s", uvc_req(req),
			      recip == 2 ? "EP sampling-frequency" : cs == 1 ? "FU mute" : cs == 2 ? "FU volume" : "AC other",
			      wIndex, cs, wLength, n < 0 ? "STALL" : "ok", detail);
	} else if (type == 1 && recip == 1) {
		int ifnum = wIndex & 0xFF, entity = wIndex >> 8, cs = wValue >> 8;
		const char *what = "?";

		if (ifnum == 1) {
			what = cs == 1 ? "VS PROBE" : cs == 2 ? "VS COMMIT" : "VS other";
			n = vs_control(req, cs, data, dlen, out);
		} else if (entity == 2) {
			what = cs == 2 ? "PU brightness" : cs == 3 ? "PU contrast" : cs == 7 ? "PU saturation" : "PU other";
			n = pu_control(req, cs, data, dlen, out);
		} else if (entity == 0 && cs == 2 && req == 0x81) {
			what = "VC request-error-code";
			out[0] = S.last_error; n = 1;
		} else {
			what = entity == 1 ? "camera terminal" : "VC other";
		}
		S.last_error = n < 0 ? 0x06 : 0;
		if (!g_quiet) {
			char detail[96] = "";
			if (ifnum == 1 && n >= 0 && (req == 0x01 || req == 0x81)) {
				const uint8_t *c = req == 0x01 ? S.probe : out;
				snprintf(detail, sizeof detail, " fmt=%d frame=%d interval=%u maxFrame=%u maxPayload=%u",
					 c[2], c[3], le32(c + 4), le32(c + 18), le32(c + 22));
			} else if (ifnum == 0 && n >= 2 && req != 0x01) {
				snprintf(detail, sizeof detail, " = %u", le16(out));
			} else if (ifnum == 0 && req == 0x01 && dlen >= 2) {
				snprintf(detail, sizeof detail, " <- %u", le16(data));
			}
			logf_("CTRL uvc  %-9s %-22s entity=%d cs=%d len=%d -> %s%s", uvc_req(req), what, entity, cs,
			      wLength, n < 0 ? "STALL" : "ok", detail);
		}
	} else if (!g_quiet) {
		logf_("CTRL ??   bm=0x%02x req=0x%02x wValue=0x%04x wIndex=0x%04x -> STALL", bm, req, wValue, wIndex);
	}
	if (n > wLength)
		n = wLength;
	return n;
}

static void complete_pending(struct pending *u)
{
	uint8_t iso[MAX_ISO_PKTS * 16];
	uint32_t total = 0;
	int i, maxpkt = S.alt == 2 ? 3072 : 1024;

	if (u->ep == 3) {
		const uint8_t packet[4] = { 0x02, 1, 0x00, S.status[0] };
		if (!g_quiet)
			logf_("BUTTON %s", packet[3] ? "pressed" : "released");
		memmove(S.status, S.status + 1, --S.nstatus);
		send_ret_submit(u->seq, 0, packet, u->len < sizeof packet ? u->len : sizeof packet, 0, NULL, 0);
		return;
	}
	if (u->npkts) {
		for (i = 0; i < u->npkts; i++) {
			int n;
			if (u->ep == 2) {
				n = next_audio(txbuf + total, u->pkt_len[i], u->period);
			} else {
				int room = u->pkt_len[i] < (uint32_t)maxpkt ? (int)u->pkt_len[i] : maxpkt;
				n = next_payload(txbuf + total, room, u->start + (uint64_t)i * u->period);
			}
			wbe32(iso + i * 16, u->pkt_off[i]);
			wbe32(iso + i * 16 + 4, u->pkt_len[i]);
			wbe32(iso + i * 16 + 8, n);
			wbe32(iso + i * 16 + 12, 0);
			total += n;
		}
		send_ret_submit(u->seq, 0, txbuf, total, u->npkts, iso, 0);
	} else {
		int n = next_payload(txbuf, u->len < 16384 ? (int)u->len : 16384, now_us());
		send_ret_submit(u->seq, 0, txbuf, n, 0, NULL, 0);
	}
	if (u->ep == 1)
		S.stat_urbs++;
}

static int is_iso(uint32_t ep, uint32_t npk)
{
	return npk && npk <= MAX_ISO_PKTS && ((ep == 1 && !S.bulk) || (ep == 2 && S.audio));
}

static void handle_submit(const uint8_t *h, const uint8_t *extra)
{
	uint32_t seq = be32(h + 4), dir = be32(h + 12), ep = be32(h + 16);
	uint32_t tlen = be32(h + 24), npk = be32(h + 32), interval = be32(h + 36);
	static uint8_t reply[1024];
	uint64_t t = now_us();
	struct pending *u;
	uint32_t i;

	if (ep == 0) {
		int n = handle_control(h + 40, dir == 0 ? extra : NULL, dir == 0 ? tlen : 0, reply);
		if (n < 0)
			send_ret_submit(seq, -EPIPE, NULL, 0, 0, NULL, 0);
		else if (dir)
			send_ret_submit(seq, 0, reply, n, 0, NULL, 0);
		else /* OUT: report the data stage as fully written; no payload follows */
			send_ret_submit(seq, 0, NULL, tlen, 0, NULL, 0);
		return;
	}
	if ((ep != 1 && !(ep == 2 && S.audio) && !(ep == 3 && S.button)) || dir != 1 || npend >= MAX_PENDING) {
		send_ret_submit(seq, -EPIPE, NULL, 0, 0, NULL, 0);
		return;
	}
	u = calloc(1, sizeof *u);
	u->seq = seq;
	u->ep = ep;
	if (is_iso(ep, npk)) {
		u->npkts = npk;
		u->period = (interval ? interval : 1) * UFRAME_US;
		for (i = 0; i < npk; i++) {
			u->pkt_off[i] = be32(extra + i * 16);
			u->pkt_len[i] = be32(extra + i * 16 + 4);
		}
		u->start = last_due[ep] > t ? last_due[ep] : t;
		u->due = last_due[ep] = u->start + (uint64_t)npk * u->period;
	} else {
		u->len = tlen;
		u->start = u->due = t; /* bulk: re-evaluated in the main loop */
		if (ep == 3 && !g_quiet)
			logf_("STATUS transfer waiting (%u bytes, interval %u)", tlen, interval);
	}
	pend[npend++] = u;
}

static void handle_unlink(const uint8_t *h)
{
	uint32_t seq = be32(h + 4), victim = be32(h + 20);
	int i;

	for (i = 0; i < npend; i++)
		if (pend[i]->seq == victim) {
			if (pend[i]->ep == 3 && !g_quiet)
				logf_("STATUS transfer cancelled");
			free(pend[i]);
			memmove(&pend[i], &pend[i + 1], (--npend - i) * sizeof pend[0]);
			send_ret_unlink(seq, -ECONNRESET);
			return;
		}
	send_ret_unlink(seq, 0);
}

/* Turn the trigger files into queued status packets. A push while the host is not listening is
 * lost, and so is an event that nobody fetches within a second. */
static void poll_button(uint64_t t)
{
	static const struct { const char *path; int n; uint8_t state[2]; } triggers[] = {
		{ "/tmp/vcam-button", 2, { 1, 0 } },
		{ "/tmp/vcam-button-press", 1, { 1 } },
		{ "/tmp/vcam-button-release", 1, { 0 } },
	};
	static uint64_t last;
	int i, k, listening;

	if (!S.button || t - last < 10000)
		return;
	last = t;
	if (S.nstatus && t - S.status_t > 1000000)
		S.nstatus = 0;
	listening = S.nstatus > 0;
	for (i = 0; i < npend; i++)
		if (pend[i]->ep == 3)
			listening = 1;
	for (i = 0; i < (int)(sizeof triggers / sizeof triggers[0]); i++) {
		if (access(triggers[i].path, F_OK) != 0)
			continue;
		unlink(triggers[i].path);
		if (!listening) {
			if (!g_quiet)
				logf_("BUTTON ignored: no status transfer is pending");
			continue;
		}
		for (k = 0; k < triggers[i].n && S.nstatus < (int)sizeof S.status; k++)
			S.status[S.nstatus++] = triggers[i].state[k];
		S.status_t = t;
	}
}

/* When can this pending transfer complete?  0 means "not yet known" (stream idle). */
static uint64_t ready_at(const struct pending *u, uint64_t t)
{
	if (u->ep == 3) /* an interrupt transfer stays pending until the button is used */
		return S.nstatus ? t : 0;
	if (u->npkts)
		return u->due;
	if (!streaming_on())
		return 0;
	return S.frame_active ? t : S.next_frame_us; /* bulk waits for the next frame */
}

static void serve(void)
{
	static uint8_t rx[1 << 20];
	size_t have = 0;
	int i;

	npend = 0;
	memset(last_due, 0, sizeof last_due);
	S.alt = S.audio_alt = 0;
	S.committed = 0;
	S.nstatus = 0;
	S.stat_t = now_us();
	for (;;) {
		struct pollfd pfd = { .fd = sock, .events = POLLIN };
		uint64_t t = now_us(), soonest = t + 20000;
		int timeout = -1, r;
		size_t off = 0;

		for (i = 0; i < npend; i++) {
			uint64_t due = ready_at(pend[i], t);
			if (due && due < soonest)
				soonest = due;
		}
		if (npend || S.button)
			timeout = soonest <= t ? 0 : (int)((soonest - t + 999) / 1000);
		r = poll(&pfd, 1, timeout);
		if (r < 0 && errno != EINTR)
			return;
		if (r > 0) {
			ssize_t n = recv(sock, rx + have, sizeof rx - have, 0);
			if (n <= 0)
				return;
			have += n;
			while (have - off >= 48) {
				const uint8_t *h = rx + off;
				uint32_t cmd = be32(h), need = 48;
				if (cmd == CMD_SUBMIT) {
					uint32_t dir = be32(h + 12), ep = be32(h + 16), tlen = be32(h + 24), npk = be32(h + 32);
					if (dir == 0)
						need += tlen;
					if (is_iso(ep, npk))
						need += npk * 16;
					if (have - off < need)
						break;
					handle_submit(h, h + 48);
				} else if (cmd == CMD_UNLINK) {
					handle_unlink(h);
				} else {
					logf_("unexpected PDU command %u, closing", cmd);
					return;
				}
				off += need;
			}
			memmove(rx, rx + off, have - off);
			have -= off;
		}

		poll_button(now_us());
		/* Complete everything that is due, oldest first, keeping per-endpoint order. */
		for (i = 0; i < npend;) {
			uint64_t due;
			t = now_us();
			due = ready_at(pend[i], t);
			if (!due || due > t) {
				i++;
				continue;
			}
			complete_pending(pend[i]);
			free(pend[i]);
			memmove(&pend[i], &pend[i + 1], (--npend - i) * sizeof pend[0]);
		}
		t = now_us();
		if (t - S.stat_t >= 2000000) {
			if (!g_quiet && (S.stat_frames || S.stat_audio))
				logf_("STATS %.1f fps, %.2f MB/s, %llu URBs/s, %d queued, audio %llu Hz", S.stat_frames / 2.0,
				      S.stat_bytes / 2e6, (unsigned long long)S.stat_urbs / 2, npend,
				      (unsigned long long)S.stat_audio / 2);
			S.stat_frames = S.stat_bytes = S.stat_urbs = S.stat_audio = 0;
			S.stat_t = t;
		}
	}
}

int main(int argc, char **argv)
{
	struct sockaddr_in addr = { .sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK) };
	int port = 3240, i, one = 1, lsock;
	const char *dump_jpeg = NULL, *dump_yuyv = NULL, *h264 = NULL, *only = NULL;

	S.button = 1;
	for (i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--bulk")) S.bulk = 1;
		else if (!strcmp(argv[i], "--audio")) S.audio = 1;
		else if (!strcmp(argv[i], "--no-button")) S.button = 0;
		else if (!strcmp(argv[i], "--h264") && i + 1 < argc) h264 = argv[++i];
		else if (!strcmp(argv[i], "--only") && i + 1 < argc) only = argv[++i];
		else if (!strcmp(argv[i], "--quiet")) g_quiet = 1;
		else if (!strcmp(argv[i], "--port") && i + 1 < argc) port = atoi(argv[++i]);
		else if (!strcmp(argv[i], "--dump-jpeg") && i + 1 < argc) dump_jpeg = argv[++i];
		else if (!strcmp(argv[i], "--dump-yuyv") && i + 1 < argc) dump_yuyv = argv[++i];
		else {
			fprintf(stderr, "usage: %s [--port N] [--bulk] [--quiet] [--audio] [--no-button] [--h264 FILE] "
					"[--only mjpg|yuy2|h264] [--dump-jpeg F] [--dump-yuyv F]\n", argv[0]);
			return 2;
		}
	}
	if (h264) {
		FILE *f = fopen(h264, "rb");
		if (!f || fseek(f, 0, SEEK_END)) {
			perror(h264);
			return 1;
		}
		S.h264_len = ftell(f);
		rewind(f);
		S.h264 = malloc(S.h264_len);
		if (fread(S.h264, 1, S.h264_len, f) != S.h264_len)
			return 1;
		fclose(f);
	}
	if (!only || !strcmp(only, "mjpg"))
		formats[nformats++] = (struct format){ K_MJPG, 2, { { 640, 480 }, { 1280, 720 } } };
	if (!only || !strcmp(only, "yuy2"))
		formats[nformats++] = (struct format){ K_YUY2, 2, { { 640, 480 }, { 320, 240 } } };
	if (h264 && (!only || !strcmp(only, "h264")))
		formats[nformats++] = (struct format){ K_H264, 1, { { 640, 480 } } };
	if (!nformats) {
		fprintf(stderr, "no formats selected (--only h264 needs --h264 FILE)\n");
		return 2;
	}
	S.audio_rate = 48000;
	g_t0 = now_us();
	S.bg = malloc(1280 * 720 * 2);
	S.yuyv = malloc(1280 * 720 * 2);
	S.jpeg = malloc(1280 * 720 * 3);
	txbuf = malloc(MAX_ISO_PKTS * 3072 + 16384);
	S.fmt = 1; S.frame = 1; S.interval = intervals[0];
	S.brightness = S.contrast = S.saturation = 128;
	jpeg_init(85);
	build_config();
	negotiate((const uint8_t[26]){ 0, 0, 1, 1 }, S.probe);
	memcpy(S.commit, S.probe, 26);

	if (dump_jpeg || dump_yuyv) {
		FILE *f = fopen(dump_jpeg ? dump_jpeg : dump_yuyv, "wb");
		S.frame_no = 12345;
		render_yuyv(640, 480);
		if (dump_jpeg)
			fwrite(S.jpeg, 1, jpeg_encode_yuyv(S.yuyv, 640, 480, S.jpeg), f);
		else
			fwrite(S.yuyv, 1, 640 * 480 * 2, f);
		fclose(f);
		return 0;
	}

	signal(SIGPIPE, SIG_IGN);
	lsock = socket(AF_INET, SOCK_STREAM, 0);
	setsockopt(lsock, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
	addr.sin_port = htons(port);
	if (bind(lsock, (struct sockaddr *)&addr, sizeof addr) || listen(lsock, 1)) {
		perror("bind/listen");
		return 1;
	}
	logf_("virtual UVC camera (%s, %d format%s%s%s) listening on 127.0.0.1:%d, config descriptor %d bytes",
	      S.bulk ? "bulk" : "isochronous", nformats, nformats > 1 ? "s" : "", S.audio ? ", audio" : "",
	      S.button ? ", button" : "", port, cfg_len);
	for (;;) {
		sock = accept(lsock, NULL, NULL);
		if (sock < 0)
			continue;
		setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
		logf_("host controller attached");
		serve();
		close(sock);
		while (npend)
			free(pend[--npend]);
		logf_("host controller detached");
	}
}
