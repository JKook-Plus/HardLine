// JNI bridge between the app and libusb/libuvc.
//
// One Session wraps one USB device whose file descriptor was opened by Android's UsbManager.
// It owns a libusb context and its event thread, an optional libuvc handle for video, and an
// optional USB Audio Class capture stream on the same device handle.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include <libusb.h>
#include "libuvc/libuvc.h"
#include "libuvc/libuvc_internal.h"
}

#define TAG "usbcam"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
std::mutex g_error_lock;
std::string g_error;

void set_error(const std::string &e) {
    std::lock_guard<std::mutex> l(g_error_lock);
    g_error = e;
    LOGW("%s", e.c_str());
}

// Native threads attach once and detach when they exit.
JNIEnv *thread_env() {
    struct Holder {
        JNIEnv *env = nullptr;
        bool attached = false;
        ~Holder() { if (attached) g_vm->DetachCurrentThread(); }
    };
    thread_local Holder h;
    if (!h.env) {
        if (g_vm->GetEnv(reinterpret_cast<void **>(&h.env), JNI_VERSION_1_6) != JNI_OK) {
            g_vm->AttachCurrentThread(&h.env, nullptr);
            h.attached = true;
        }
    }
    return h.env;
}

struct AudioAlt {
    int iface = 0, alt = 0, ep = 0, maxPacket = 0;
    int channels = 0, subframe = 0, bits = 0;
    int version = 1, acIface = 0, clockId = 0, featureUnit = 0;
    std::vector<int> rates;      // discrete rates, or {min, max} when continuous
    bool continuous = false;
};

struct Session {
    libusb_context *usb = nullptr;
    uvc_context_t *uvc = nullptr;
    uvc_device_handle_t *devh = nullptr;
    libusb_device_handle *usbh = nullptr;
    std::thread events;
    std::atomic<bool> running{false};

    jobject videoListener = nullptr;
    jmethodID onFrame = nullptr, onButton = nullptr;
    std::mutex listenerLock;     // the button reports on the event thread, whatever the stream is doing
    std::atomic<bool> streaming{false};
    int streamInterface = -1;

    std::vector<AudioAlt> audioAlts;
    AudioAlt audio;
    int audioRate = 0;
    jobject audioListener = nullptr;
    jmethodID onAudio = nullptr;
    std::atomic<bool> audioOn{false};
    std::atomic<int> audioPending{0};
    std::vector<libusb_transfer *> audioTransfers;
    std::vector<int16_t> pcm;
};

void json_str(std::string &out, const char *key, const std::string &value) {
    out += "\"";
    out += key;
    out += "\":\"";
    for (char c : value) {
        if (c == '"' || c == '\\') out += '\\';
        if (static_cast<unsigned char>(c) >= 0x20) out += c;
    }
    out += "\"";
}

void json_int(std::string &out, const char *key, long long value) {
    out += "\"";
    out += key;
    out += "\":";
    out += std::to_string(value);
}

// ---------------------------------------------------------------- audio descriptors

void parse_audio(Session *s) {
    libusb_config_descriptor *cfg = nullptr;
    if (libusb_get_active_config_descriptor(libusb_get_device(s->usbh), &cfg) != 0) return;
    int version = 1, acIface = 0, clockId = 0, featureUnit = 0;
    for (int i = 0; i < cfg->bNumInterfaces; i++) {
        const libusb_interface &itf = cfg->interface[i];
        for (int a = 0; a < itf.num_altsetting; a++) {
            const libusb_interface_descriptor &d = itf.altsetting[a];
            if (d.bInterfaceClass != 1) continue;
            const uint8_t *p = d.extra;
            const uint8_t *end = p + d.extra_length;
            if (d.bInterfaceSubClass == 1) {        // AudioControl
                acIface = d.bInterfaceNumber;
                version = d.bInterfaceProtocol == 0x20 ? 2 : 1;
                clockId = featureUnit = 0;
                for (; p + 2 < end && p[0] >= 3; p += p[0]) {
                    if (p[1] != 0x24) continue;
                    if (p[2] == 0x06 && !featureUnit) featureUnit = p[3];
                    if (version == 2 && p[2] == 0x0A && !clockId) clockId = p[3];
                }
            } else if (d.bInterfaceSubClass == 2 && d.bNumEndpoints > 0) {   // AudioStreaming
                AudioAlt alt;
                alt.iface = d.bInterfaceNumber;
                alt.alt = d.bAlternateSetting;
                alt.version = version;
                alt.acIface = acIface;
                alt.clockId = clockId;
                alt.featureUnit = featureUnit;
                for (int e = 0; e < d.bNumEndpoints; e++) {
                    const libusb_endpoint_descriptor &ep = d.endpoint[e];
                    if ((ep.bEndpointAddress & 0x80) && (ep.bmAttributes & 3) == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
                        alt.ep = ep.bEndpointAddress;
                        alt.maxPacket = (ep.wMaxPacketSize & 0x7FF) * (1 + ((ep.wMaxPacketSize >> 11) & 3));
                    }
                }
                bool pcm = false;
                for (; p + 2 < end && p[0] >= 3; p += p[0]) {
                    if (p[1] != 0x24) continue;
                    if (version == 1) {
                        if (p[2] == 0x01 && p[0] >= 7) pcm = (p[5] | p[6] << 8) == 1;
                        if (p[2] == 0x02 && p[0] >= 8 && p[3] == 1) {
                            alt.channels = p[4];
                            alt.subframe = p[5];
                            alt.bits = p[6];
                            int n = p[7];
                            alt.continuous = n == 0;
                            for (int k = 0; k < (n ? n : 2) && 8 + 3 * k + 2 < p[0]; k++) {
                                const uint8_t *f = p + 8 + 3 * k;
                                alt.rates.push_back(f[0] | f[1] << 8 | f[2] << 16);
                            }
                        }
                    } else {
                        if (p[2] == 0x01 && p[0] >= 16) {
                            pcm = p[5] == 1 && (p[6] & 1);
                            alt.channels = p[10];
                        }
                        if (p[2] == 0x02 && p[0] >= 6 && p[3] == 1) {
                            alt.subframe = p[4];
                            alt.bits = p[5];
                        }
                    }
                }
                if (pcm && alt.ep && alt.channels > 0 && alt.subframe > 0) s->audioAlts.push_back(alt);
            }
        }
    }
    libusb_free_config_descriptor(cfg);
}

// UAC2 devices publish their sample rates through the clock source's RANGE request.
void query_clock_rates(Session *s, AudioAlt &alt) {
    if (alt.version != 2 || !alt.rates.empty()) return;
    uint8_t buf[2 + 12 * 16] = {0};
    int n = libusb_control_transfer(s->usbh, 0xA1, 0x02, 0x0100, alt.clockId << 8 | alt.acIface, buf, sizeof buf, 500);
    if (n >= 2) {
        int count = buf[0] | buf[1] << 8;
        for (int k = 0; k < count && 2 + 12 * k + 12 <= n; k++) {
            const uint8_t *r = buf + 2 + 12 * k;
            int lo = r[0] | r[1] << 8 | r[2] << 16 | r[3] << 24;
            int hi = r[4] | r[5] << 8 | r[6] << 16 | r[7] << 24;
            alt.rates.push_back(lo);
            if (hi != lo) alt.rates.push_back(hi);
        }
    }
    if (alt.rates.empty()) alt.rates.push_back(48000);
}

int pick_rate(const AudioAlt &alt, int wanted) {
    if (alt.rates.empty()) return wanted;
    if (alt.continuous && alt.rates.size() >= 2)
        return wanted < alt.rates[0] ? alt.rates[0] : wanted > alt.rates[1] ? alt.rates[1] : wanted;
    for (int pref : {wanted, 48000, 44100})
        for (int r : alt.rates) if (r == pref) return r;
    int best = alt.rates[0];
    for (int r : alt.rates) if (r <= 96000 && r > best) best = r;
    return best;
}

void LIBUSB_CALL audio_cb(libusb_transfer *t) {
    auto *s = static_cast<Session *>(t->user_data);
    if (s->audioOn && t->status == LIBUSB_TRANSFER_COMPLETED && s->audioListener) {
        const AudioAlt &a = s->audio;
        int frameBytes = a.channels * a.subframe;
        s->pcm.clear();
        for (int i = 0; i < t->num_iso_packets; i++) {
            const libusb_iso_packet_descriptor &pk = t->iso_packet_desc[i];
            if (pk.status != LIBUSB_TRANSFER_COMPLETED || pk.actual_length < static_cast<unsigned>(frameBytes)) continue;
            const uint8_t *p = libusb_get_iso_packet_buffer_simple(t, i);
            for (unsigned off = 0; off + frameBytes <= pk.actual_length; off += frameBytes) {
                for (int ch = 0; ch < 2; ch++) {
                    const uint8_t *q = p + off + (ch < a.channels ? ch : a.channels - 1) * a.subframe;
                    int16_t v;
                    switch (a.subframe) {
                        case 1: v = static_cast<int16_t>((q[0] - 128) << 8); break;
                        case 2: v = static_cast<int16_t>(q[0] | q[1] << 8); break;
                        case 3: v = static_cast<int16_t>(q[1] | q[2] << 8); break;
                        default: v = static_cast<int16_t>(q[2] | q[3] << 8); break;
                    }
                    s->pcm.push_back(v);
                }
            }
        }
        if (!s->pcm.empty()) {
            JNIEnv *env = thread_env();
            jobject buf = env->NewDirectByteBuffer(s->pcm.data(), static_cast<jlong>(s->pcm.size() * 2));
            env->CallVoidMethod(s->audioListener, s->onAudio, buf, static_cast<jint>(s->pcm.size() / 2), s->audioRate);
            env->DeleteLocalRef(buf);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }
    if (s->audioOn && t->status != LIBUSB_TRANSFER_NO_DEVICE && libusb_submit_transfer(t) == 0) return;
    s->audioPending--;
}

void stop_audio(Session *s) {
    if (!s->audioOn.exchange(false)) return;
    for (libusb_transfer *t : s->audioTransfers) libusb_cancel_transfer(t);
    for (int i = 0; i < 200 && s->audioPending > 0; i++) std::this_thread::sleep_for(std::chrono::milliseconds(10));
    for (libusb_transfer *t : s->audioTransfers) {
        free(t->buffer);
        libusb_free_transfer(t);
    }
    s->audioTransfers.clear();
    libusb_set_interface_alt_setting(s->usbh, s->audio.iface, 0);
    libusb_release_interface(s->usbh, s->audio.iface);
}

// ---------------------------------------------------------------- video

void frame_cb(uvc_frame_t *f, void *user) {
    auto *s = static_cast<Session *>(user);
    if (!s->streaming || !s->videoListener || !f->data_bytes) return;
    JNIEnv *env = thread_env();
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    jobject buf = env->NewDirectByteBuffer(f->data, static_cast<jlong>(f->data_bytes));
    env->CallVoidMethod(s->videoListener, s->onFrame, buf, static_cast<jint>(f->data_bytes),
                        static_cast<jint>(f->width), static_cast<jint>(f->height),
                        static_cast<jlong>(ts.tv_sec) * 1000000000LL + ts.tv_nsec);
    env->DeleteLocalRef(buf);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void button_cb(int button, int state, void *user) {
    auto *s = static_cast<Session *>(user);
    LOGI("camera button %s (interface %d)", state ? "pressed" : "released", button);
    std::lock_guard<std::mutex> l(s->listenerLock);
    if (!s->videoListener) return;
    JNIEnv *env = thread_env();
    env->CallVoidMethod(s->videoListener, s->onButton, button, state);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void stop_video(Session *s, JNIEnv *env) {
    if (s->streaming.exchange(false) && s->devh) uvc_stop_streaming(s->devh);
    std::lock_guard<std::mutex> l(s->listenerLock);
    if (s->videoListener) {
        env->DeleteGlobalRef(s->videoListener);
        s->videoListener = nullptr;
    }
}

uvc_streaming_interface_t *stream_interface(Session *s, int number) {
    uvc_streaming_interface_t *sif;
    DL_FOREACH(s->devh->info->stream_ifs, sif) {
        if (number < 0 || sif->bInterfaceNumber == number) return sif;
    }
    return nullptr;
}

bool is_bulk(Session *s, int ifaceNumber) {
    libusb_config_descriptor *cfg = nullptr;
    bool bulk = false;
    if (libusb_get_active_config_descriptor(libusb_get_device(s->usbh), &cfg) != 0) return false;
    for (int i = 0; i < cfg->bNumInterfaces; i++) {
        const libusb_interface &itf = cfg->interface[i];
        if (itf.num_altsetting && itf.altsetting[0].bInterfaceNumber == ifaceNumber) bulk = itf.num_altsetting < 2;
    }
    libusb_free_config_descriptor(cfg);
    return bulk;
}

// ---------------------------------------------------------------- JNI

jlong n_open(JNIEnv *, jclass, jint fd, jboolean video) {
    auto *s = new Session();
    libusb_init_option opts[1] = {};
    opts[0].option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
    int r = libusb_init_context(&s->usb, opts, 1);
    if (r != 0) {
        set_error(std::string("libusb init: ") + libusb_strerror(r));
        delete s;
        return 0;
    }
    if (video) {
        uvc_error_t e = uvc_init(&s->uvc, s->usb);
        if (e == UVC_SUCCESS) e = uvc_wrap(fd, s->uvc, &s->devh);
        if (e != UVC_SUCCESS) {
            set_error(std::string("open camera: ") + uvc_strerror(e));
            if (s->uvc) uvc_exit(s->uvc);
            libusb_exit(s->usb);
            delete s;
            return 0;
        }
        s->usbh = uvc_get_libusb_handle(s->devh);
        // Registered once, before the event thread exists; button_cb finds the current listener itself.
        uvc_set_button_callback(s->devh, button_cb, s);
    } else {
        r = libusb_wrap_sys_device(s->usb, fd, &s->usbh);
        if (r != 0) {
            set_error(std::string("open device: ") + libusb_strerror(r));
            libusb_exit(s->usb);
            delete s;
            return 0;
        }
    }
    parse_audio(s);
    s->running = true;
    s->events = std::thread([s] {
        thread_env();
        while (s->running) {
            timeval tv{0, 100000};
            libusb_handle_events_timeout_completed(s->usb, &tv, nullptr);
        }
    });
    return reinterpret_cast<jlong>(s);
}

void n_close(JNIEnv *env, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    if (!s) return;
    // libuvc never ends its status transfer and would start it again while the device closes.
    if (s->devh && s->devh->status_xfer) libusb_cancel_transfer(s->devh->status_xfer);
    stop_audio(s);
    if (s->audioListener) env->DeleteGlobalRef(s->audioListener);
    stop_video(s, env);
    if (s->devh) uvc_close(s->devh);
    else if (s->usbh) libusb_close(s->usbh);
    s->running = false;
    if (s->events.joinable()) s->events.join();
    if (s->uvc) uvc_exit(s->uvc);
    libusb_exit(s->usb);
    delete s;
}

jstring n_last_error(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> l(g_error_lock);
    return env->NewStringUTF(g_error.c_str());
}

jstring n_describe(JNIEnv *env, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    std::string j = "{";
    libusb_device_descriptor dd{};
    libusb_get_device_descriptor(libusb_get_device(s->usbh), &dd);
    json_int(j, "vendorId", dd.idVendor);
    j += ",";
    json_int(j, "productId", dd.idProduct);
    j += ",";
    json_int(j, "bcdUsb", dd.bcdUSB);
    j += ",";
    json_int(j, "speed", libusb_get_device_speed(libusb_get_device(s->usbh)));
    j += ",\"streams\":[";
    if (s->devh) {
        bool firstIf = true;
        uvc_streaming_interface_t *sif;
        DL_FOREACH(s->devh->info->stream_ifs, sif) {
            if (!firstIf) j += ",";
            firstIf = false;
            j += "{";
            json_int(j, "interface", sif->bInterfaceNumber);
            j += ",\"bulk\":";
            j += is_bulk(s, sif->bInterfaceNumber) ? "true" : "false";
            j += ",";
            json_int(j, "stillMethod", sif->bStillCaptureMethod);
            j += ",\"formats\":[";
            bool firstFmt = true;
            uvc_format_desc_t *fd;
            DL_FOREACH(sif->format_descs, fd) {
                if (!firstFmt) j += ",";
                firstFmt = false;
                char fourcc[5] = {0};
                memcpy(fourcc, fd->fourccFormat, 4);
                for (char &c : fourcc) if (c && (c < 0x20 || c > 0x7E)) c = '?';
                j += "{";
                json_int(j, "index", fd->bFormatIndex);
                j += ",";
                json_str(j, "fourcc", fourcc);
                j += ",";
                json_int(j, "defaultFrame", fd->bDefaultFrameIndex);
                j += ",\"frames\":[";
                bool firstFrame = true;
                uvc_frame_desc_t *fr;
                DL_FOREACH(fd->frame_descs, fr) {
                    if (!firstFrame) j += ",";
                    firstFrame = false;
                    j += "{";
                    json_int(j, "index", fr->bFrameIndex);
                    j += ",";
                    json_int(j, "width", fr->wWidth);
                    j += ",";
                    json_int(j, "height", fr->wHeight);
                    j += ",";
                    json_int(j, "defaultInterval", fr->dwDefaultFrameInterval);
                    j += ",\"intervals\":[";
                    if (fr->intervals) {
                        for (uint32_t *iv = fr->intervals; *iv; iv++) {
                            if (iv != fr->intervals) j += ",";
                            j += std::to_string(*iv);
                        }
                    } else if (fr->dwMinFrameInterval) {
                        // Continuous range: offer the common rates that fall inside it.
                        bool any = false;
                        for (uint32_t fps : {60u, 50u, 30u, 25u, 20u, 15u, 10u, 5u}) {
                            uint32_t iv = 10000000u / fps;
                            if (iv < fr->dwMinFrameInterval || iv > fr->dwMaxFrameInterval) continue;
                            if (any) j += ",";
                            any = true;
                            j += std::to_string(iv);
                        }
                    }
                    j += "]}";
                }
                j += "]}";
            }
            j += "]}";
        }
    }
    j += "]";
    if (s->devh) {
        j += ",";
        json_int(j, "uvcVersion", uvc_get_spec_version(s->devh));
        if (const uvc_processing_unit_t *pu = uvc_get_processing_units(s->devh)) {
            j += ",\"processingUnit\":{";
            json_int(j, "id", pu->bUnitID);
            j += ",";
            json_int(j, "controls", static_cast<long long>(pu->bmControls));
            j += "}";
        }
        if (const uvc_input_terminal_t *ct = uvc_get_camera_terminal(s->devh)) {
            j += ",\"cameraTerminal\":{";
            json_int(j, "id", ct->bTerminalID);
            j += ",";
            json_int(j, "controls", static_cast<long long>(ct->bmControls));
            j += "}";
        }
    }
    j += ",\"audio\":[";
    for (size_t i = 0; i < s->audioAlts.size(); i++) {
        const AudioAlt &a = s->audioAlts[i];
        if (i) j += ",";
        j += "{";
        json_int(j, "channels", a.channels);
        j += ",";
        json_int(j, "bits", a.bits);
        j += ",";
        json_int(j, "version", a.version);
        j += ",\"rates\":[";
        for (size_t k = 0; k < a.rates.size(); k++) {
            if (k) j += ",";
            j += std::to_string(a.rates[k]);
        }
        j += "]}";
    }
    j += "]}";
    return env->NewStringUTF(j.c_str());
}

jint n_start_video(JNIEnv *env, jclass, jlong h, jint iface, jint format, jint frame, jint interval, jobject listener) {
    auto *s = reinterpret_cast<Session *>(h);
    if (!s || !s->devh) return UVC_ERROR_NO_DEVICE;
    stop_video(s, env);
    uvc_streaming_interface_t *sif = stream_interface(s, iface);
    if (!sif) return UVC_ERROR_INVALID_PARAM;

    uvc_stream_ctrl_t ctrl{};
    ctrl.bInterfaceNumber = sif->bInterfaceNumber;
    uvc_claim_if(s->devh, ctrl.bInterfaceNumber);
    uvc_query_stream_ctrl(s->devh, &ctrl, 1, UVC_GET_MAX);
    ctrl.bInterfaceNumber = sif->bInterfaceNumber;
    ctrl.bmHint = 1;
    ctrl.bFormatIndex = static_cast<uint8_t>(format);
    ctrl.bFrameIndex = static_cast<uint8_t>(frame);
    ctrl.dwFrameInterval = static_cast<uint32_t>(interval);
    uvc_error_t e = uvc_probe_stream_ctrl(s->devh, &ctrl);
    if (e != UVC_SUCCESS) {
        set_error(std::string("negotiate: ") + uvc_strerror(e));
        return e;
    }
    jclass cls = env->GetObjectClass(listener);
    s->onFrame = env->GetMethodID(cls, "onFrame", "(Ljava/nio/ByteBuffer;IIIJ)V");
    s->onButton = env->GetMethodID(cls, "onButton", "(II)V");
    {
        std::lock_guard<std::mutex> l(s->listenerLock);
        s->videoListener = env->NewGlobalRef(listener);
    }
    s->streamInterface = sif->bInterfaceNumber;
    s->streaming = true;
    e = uvc_start_streaming(s->devh, &ctrl, frame_cb, s, 0);
    if (e != UVC_SUCCESS) {
        stop_video(s, env);
        set_error(std::string("start stream: ") + uvc_strerror(e));
        return e;
    }
    return 0;
}

void n_stop_video(JNIEnv *env, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    if (s) stop_video(s, env);
}

// request: UVC request code (0x01 SET_CUR, 0x81 GET_CUR, 0x82 MIN, 0x83 MAX, 0x84 RES, 0x87 DEF).
jint n_control(JNIEnv *env, jclass, jlong h, jint unit, jint selector, jint request, jbyteArray data) {
    auto *s = reinterpret_cast<Session *>(h);
    if (!s || !s->devh) return UVC_ERROR_NO_DEVICE;
    jsize len = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    int r = request == 0x01
            ? uvc_set_ctrl(s->devh, static_cast<uint8_t>(unit), static_cast<uint8_t>(selector), bytes, len)
            : uvc_get_ctrl(s->devh, static_cast<uint8_t>(unit), static_cast<uint8_t>(selector), bytes, len,
                           static_cast<uvc_req_code>(request));
    env->ReleaseByteArrayElements(data, bytes, 0);
    return r;
}

jint n_start_audio(JNIEnv *env, jclass, jlong h, jint wantedRate, jobject listener) {
    auto *s = reinterpret_cast<Session *>(h);
    if (!s || s->audioAlts.empty()) return -1;
    stop_audio(s);
    int best = -1, bestScore = -1;
    for (size_t i = 0; i < s->audioAlts.size(); i++) {
        const AudioAlt &a = s->audioAlts[i];
        int score = (a.channels == 2 ? 20 : a.channels == 1 ? 10 : 5) + (a.bits == 16 ? 4 : a.bits == 24 ? 2 : 0);
        if (score > bestScore) {
            bestScore = score;
            best = static_cast<int>(i);
        }
    }
    AudioAlt &a = s->audioAlts[best];
    query_clock_rates(s, a);
    s->audio = a;
    s->audioRate = pick_rate(a, wantedRate);

    libusb_detach_kernel_driver(s->usbh, a.iface);
    int r = libusb_claim_interface(s->usbh, a.iface);
    if (r != 0) {
        set_error(std::string("claim audio interface: ") + libusb_strerror(r));
        return r;
    }
    if (a.featureUnit && a.version == 1) {      // unmute and set 0 dB on the master channel
        uint8_t vol[2] = {0, 0}, mute[1] = {0};
        libusb_control_transfer(s->usbh, 0x21, 0x01, 0x0200, a.featureUnit << 8 | a.acIface, vol, 2, 300);
        libusb_control_transfer(s->usbh, 0x21, 0x01, 0x0100, a.featureUnit << 8 | a.acIface, mute, 1, 300);
    }
    r = libusb_set_interface_alt_setting(s->usbh, a.iface, a.alt);
    if (r != 0) {
        set_error(std::string("select audio format: ") + libusb_strerror(r));
        libusb_release_interface(s->usbh, a.iface);
        return r;
    }
    uint8_t rate[4] = {static_cast<uint8_t>(s->audioRate), static_cast<uint8_t>(s->audioRate >> 8),
                       static_cast<uint8_t>(s->audioRate >> 16), static_cast<uint8_t>(s->audioRate >> 24)};
    if (a.version == 1) libusb_control_transfer(s->usbh, 0x22, 0x01, 0x0100, a.ep, rate, 3, 300);
    else libusb_control_transfer(s->usbh, 0x21, 0x01, 0x0100, a.clockId << 8 | a.acIface, rate, 4, 300);

    if (s->audioListener) env->DeleteGlobalRef(s->audioListener);
    s->audioListener = env->NewGlobalRef(listener);
    s->onAudio = env->GetMethodID(env->GetObjectClass(listener), "onAudio", "(Ljava/nio/ByteBuffer;II)V");
    s->audioOn = true;
    const int packets = 8, transfers = 6;
    for (int i = 0; i < transfers; i++) {
        libusb_transfer *t = libusb_alloc_transfer(packets);
        auto *buf = static_cast<unsigned char *>(malloc(static_cast<size_t>(a.maxPacket) * packets));
        libusb_fill_iso_transfer(t, s->usbh, static_cast<unsigned char>(a.ep), buf, a.maxPacket * packets, packets,
                                 audio_cb, s, 1000);
        libusb_set_iso_packet_lengths(t, static_cast<unsigned>(a.maxPacket));
        s->audioTransfers.push_back(t);
        if (libusb_submit_transfer(t) == 0) s->audioPending++;
    }
    if (s->audioPending == 0) {
        set_error("audio stream could not be started");
        stop_audio(s);
        return -2;
    }
    LOGI("audio: %d Hz, %d ch, %d bit (UAC%d)", s->audioRate, a.channels, a.bits, a.version);
    return s->audioRate;
}

void n_stop_audio(JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    if (s) stop_audio(s);
}

const JNINativeMethod kMethods[] = {
        {"open",       "(IZ)J",                                                  (void *) n_open},
        {"close",      "(J)V",                                                   (void *) n_close},
        {"lastError",  "()Ljava/lang/String;",                                   (void *) n_last_error},
        {"describe",   "(J)Ljava/lang/String;",                                  (void *) n_describe},
        {"startVideo", "(JIIIILdev/hardline/usb/VideoListener;)I",           (void *) n_start_video},
        {"stopVideo",  "(J)V",                                                   (void *) n_stop_video},
        {"control",    "(JIII[B)I",                                              (void *) n_control},
        {"startAudio", "(JILdev/hardline/usb/AudioListener;)I",              (void *) n_start_audio},
        {"stopAudio",  "(J)V",                                                   (void *) n_stop_audio},
};

}  // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("dev/hardline/usb/UsbNative");
    if (!cls || env->RegisterNatives(cls, kMethods, sizeof kMethods / sizeof kMethods[0]) != 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}
