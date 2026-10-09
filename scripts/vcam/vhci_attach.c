/*
 * vhci_attach - connect a TCP socket to a USB/IP device server and hand it to the kernel's
 * vhci_hcd, which then treats the peer as a USB device on a virtual root-hub port.
 *
 * This skips the usual OP_REQ_IMPORT handshake because the peer (uvc_usbip) does not need it:
 * the kernel only ever speaks the URB protocol on the socket.
 *
 *   vhci_attach <server-ip> <tcp-port> [vhci-port=0] [speed=3 (high)]
 *
 * Built statically on the host and run as root inside the emulator.
 */
#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define ATTACH "/sys/devices/platform/vhci_hcd.0/attach"

int main(int argc, char **argv)
{
	struct sockaddr_in addr = { .sin_family = AF_INET };
	unsigned port, speed, devid = 1u << 16 | 2;
	char buf[64];
	int s, fd, n, one = 1;

	if (argc < 3 || inet_pton(AF_INET, argv[1], &addr.sin_addr) != 1) {
		fprintf(stderr, "usage: %s <server-ip> <tcp-port> [vhci-port=0] [speed=3]\n", argv[0]);
		return 2;
	}
	addr.sin_port = htons(atoi(argv[2]));
	port = argc > 3 ? atoi(argv[3]) : 0;
	speed = argc > 4 ? atoi(argv[4]) : 3;

	s = socket(AF_INET, SOCK_STREAM, 0);
	if (s < 0 || connect(s, (struct sockaddr *)&addr, sizeof addr)) {
		perror("connect");
		return 1;
	}
	setsockopt(s, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
	setsockopt(s, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof one);

	fd = open(ATTACH, O_WRONLY);
	if (fd < 0) {
		perror(ATTACH);
		return 1;
	}
	n = snprintf(buf, sizeof buf, "%u %d %u %u", port, s, devid, speed);
	if (write(fd, buf, n) != n) {
		perror("attach");
		return 1;
	}
	printf("attached to vhci port %u\n", port);
	return 0;
}
