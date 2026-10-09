/* Runs inside the emulator: joins a UDP port of an app on the guest to a UDP port on the host.
 * The guest must speak first because the emulator's network only lets the guest open UDP flows.
 *   udprelay <host-port> <app-port>
 */
#include <arpa/inet.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>

static int dial(const char *ip, int port) {
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(port)};
    inet_pton(AF_INET, ip, &a.sin_addr);
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0 || connect(s, (struct sockaddr *)&a, sizeof a) < 0) { perror("socket"); exit(1); }
    return s;
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: udprelay <host-port> <app-port>\n"); return 2; }
    int host = dial("10.0.2.2", atoi(argv[1])), app = dial("127.0.0.1", atoi(argv[2]));
    struct pollfd fds[2] = {{host, POLLIN, 0}, {app, POLLIN, 0}};
    char buf[2048];
    for (;;) {
        if (poll(fds, 2, 1000) == 0) { send(host, "k", 1, 0); continue; }   /* keep the flow open */
        if (fds[0].revents & POLLIN) { ssize_t n = recv(host, buf, sizeof buf, 0); if (n > 0) send(app, buf, n, 0); }
        if (fds[1].revents & POLLIN) { ssize_t n = recv(app, buf, sizeof buf, 0); if (n > 0) send(host, buf, n, 0); }
    }
}
