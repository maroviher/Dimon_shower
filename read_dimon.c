#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>

#include <string.h>

#include <sys/types.h>
#include <sys/time.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include <stdbool.h>
#include <inttypes.h>

#define VIDEO_DECODE_PORT 130


int sockfd = -1;

int
SetupListenSocket (struct in_addr* ip, unsigned short port, unsigned short rcv_timeout, bool bVerbose)
{
   int sfd = -1;
   struct sockaddr_in saddr={};
   saddr.sin_family = AF_INET;
   saddr.sin_port = htons(port);
   saddr.sin_addr = *ip;

   int sockListen = socket(AF_INET, SOCK_STREAM, 0);
   if (sockListen >= 0)
   {
      int iTmp = 1;
      setsockopt(sockListen, SOL_SOCKET, SO_REUSEADDR, &iTmp, sizeof(int)); //no error handling, just go on
      if (bind(sockListen, (struct sockaddr *) &saddr, sizeof(saddr)) >= 0)
      {
         while ((-1 == (iTmp = listen(sockListen, 0))) && (EINTR == errno))
            ;
         if (-1 != iTmp)
         {
            fprintf(stderr, "Waiting for a TCP connection on %s:%"SCNu16"...", inet_ntoa(saddr.sin_addr), ntohs(saddr.sin_port));
            struct sockaddr_in cli_addr;
            socklen_t clilen = sizeof(cli_addr);
            while ((-1 == (sfd = accept(sockListen, (struct sockaddr *) &cli_addr, &clilen))) && (EINTR == errno))
               ;
            if (sfd >= 0)
            {
               struct timeval timeout;
               timeout.tv_sec = rcv_timeout;
               timeout.tv_usec = 0;
               if (setsockopt(sfd, SOL_SOCKET, SO_RCVTIMEO, (char *) &timeout, sizeof(timeout)) < 0)
                  fprintf(stderr, "setsockopt failed\n");
               fprintf(stderr, "Client connected from %s:%"SCNu16"\n", inet_ntoa(cli_addr.sin_addr), ntohs(cli_addr.sin_port));
            }
            else
               fprintf(stderr, "Error on accept: %s\n", strerror(errno));
         }
         else //if (-1 != iTmp)
         {
            fprintf(stderr, "Error trying to listen on a socket: %s\n", strerror(errno));
         }
      }
      else //if (bind(sockListen, (struct sockaddr *) &saddr, sizeof(saddr)) >= 0)
      {
         fprintf(stderr, "Error on binding socket: %s\n", strerror(errno));
      }
   }
   else //if (sockListen >= 0)
   {
      fprintf(stderr, "Error creating socket: %s\n", strerror(errno));
   }

   if (sockListen >= 0) //regardless success or error
      close(sockListen); //do not listen on a given port anymore

   return sfd;
}



int
ConnectToHost (struct in_addr* ip, unsigned short port, bool bVerbose)
{
   int sfd = -1;

   struct sockaddr_in saddr = {};
   saddr.sin_family = AF_INET;
   saddr.sin_port = htons(port);
   saddr.sin_addr = *ip;

   bool bConnected = false;
   int iConnectCnt = 0;
   while ((!bConnected) && (iConnectCnt++ < 10000000))
   {
      if (0 <= (sfd = socket(AF_INET, SOCK_STREAM, 0)))
      {
         fcntl(sfd, F_SETFL, O_NONBLOCK);

         if(bVerbose)
            fprintf(stderr, "Connecting(%d) to %s:%hu...", iConnectCnt, inet_ntoa(saddr.sin_addr), port);
         int iTmp = connect(sfd, (struct sockaddr *) &saddr, sizeof(struct sockaddr_in));
         if ((iTmp == -1) && (errno != EINPROGRESS))
         {
            fprintf(stderr, "connect error: %s\n", strerror(errno));
            return 1;
         }
         if (iTmp == 0)
         {
            bConnected = true;
            continue; //connected immediately, not realistic
         }
         fd_set fdset;
         FD_ZERO(&fdset);
         FD_SET(sfd, &fdset);
         struct timeval tv;
         tv.tv_sec = 1;
         tv.tv_usec = 0;

         iTmp = select(sfd + 1, NULL, &fdset, NULL, &tv);
         switch (iTmp)
         {
            case 1: // data to read
            {
               int so_error;
               socklen_t len = sizeof(so_error);
               getsockopt(sfd, SOL_SOCKET, SO_ERROR, &so_error, &len);
               if (so_error == 0)
               {
                  if(bVerbose)
                     fprintf(stderr, "connected, receiving data\n");
                  bConnected = true;
                  continue;
               }
               else
               { // error
                  if ((ECONNREFUSED == so_error)||
                      (EHOSTUNREACH == so_error))
                  {
                     if(bVerbose)
                        fprintf(stderr, "%s\n", strerror(so_error));
                     close(sfd);
                     usleep(100000);
                     continue;
                  }

                  fprintf(stderr, "socket select %d, %s\n", so_error, strerror(so_error));
                  return -1;
               }
            }
               break;
            case 0: //timeout
               if(bVerbose)
                  fprintf(stderr, "timeout connecting\n");
               close(sfd);
               break;
         }
      }
      else
      {
         fprintf(stderr, "Error creating socket: %s\n", strerror(errno));
         return -1;
      }
   }

   if (bConnected && (-1 != sfd))
   {
      int flags = fcntl(sfd, F_GETFL, 0);
      if (0 != fcntl(sfd, F_SETFL, flags ^ O_NONBLOCK))
      {
         fprintf(stderr, "fcntl O_NONBLOCK");
         close(sfd);
         exit(134);
      }
      return sfd;
   }
   return -1;
}

void
error (char *msg)
{
   perror(msg);
   exit(1);
}

unsigned int ui = 0;

void show_usage_and_exit(char** argv)
{
   /*char* bname = strdupa(argv[0]);
   fprintf(stderr,
         "Usage: %s [-l port] [-t timeout sec] -p port"
         "\n\tconnect: %s -h 1.2.3.4 -l -p 1234 -t 3"
         "\n\twait for incoming: %s -l -p 1234\n", bname, bname, bname);*/
   exit(EXIT_FAILURE);
}

void ReadToBuf(int sockfd, unsigned char* buf, unsigned int iToRead) {
	int iRecv_all = 0;
	while(iToRead > iRecv_all) {
		 int iRecv = recv(sockfd, buf+iRecv_all, iToRead-iRecv_all, 0);
		 iRecv_all += iRecv;
		 if(iRecv < 1) {
				 fprintf(stderr, "recv error=%"SCNu32, iRecv);
				 exit(11);
		 }
	 }
}

unsigned int GetTickCount() {
	struct timeval tv;
	if(gettimeofday(&tv, NULL) == 0) {
			return (tv.tv_sec * 1000000) + (tv.tv_usec / 1);
	}
	return 12345678;
}

int
main (int argc, char** argv)
{
   if(argc < 3)
      show_usage_and_exit(argv);

   bool bListen = false, bVerbose = false;
   unsigned short port, recv_timeout = 3;
   struct in_addr ip={};
   int opt;
   while ((opt = getopt(argc, argv, "t:vlh:p:")) != -1)
   {
      switch (opt)
      {
         case 'l':
            bListen = true;
            break;
         case 'v':
            bVerbose = true;
            break;
         case 'h':
            if (0 == inet_aton(optarg, &ip))
            {
               fprintf(stderr, "inet_aton failed. %s is not a valid IPv4 address\n", optarg);
               exit(134);
            }
            break;
         case 'p':
            if (1 != sscanf(optarg, "%hu", &port))
            {
               fprintf(stderr, "error port\n");
               exit(EXIT_FAILURE);
            }
            break;
         case 't':
            if (1 != sscanf(optarg, "%hu", &recv_timeout))
            {
               fprintf(stderr, "error recv_timeout\n");
               exit(EXIT_FAILURE);
            }
            break;
         default: /* '?' */
            show_usage_and_exit(argv);
      }
   }

   if(bListen)
      sockfd = SetupListenSocket(&ip, port, 3, bVerbose);
   else
      sockfd = ConnectToHost(&ip, port, bVerbose);

   if (sockfd < 0)
   {
      fprintf(stderr, "connect failed");
      exit(133);
   }
   unsigned char buf[1024*1024];

   unsigned tick_prev = GetTickCount();
   int length_prev;
   while (1) {
		 //read tag
		 unsigned char tag;
		 if( 1 != recv(sockfd, &tag, 1, 0)) {
				 exit(11);
		 }
		 unsigned char tags[] = {1,4,5,6};
		 bool bTagOk = false;
		 for(int i = 0; i < sizeof(tags)/sizeof(tag); i++) {
				 if(tag == tags[i]) {
						 bTagOk = true;
						 break;
				 }
		 }
		 if(bTagOk == false) {
				 fprintf(stderr, "wrong tag=%"SCNu8" last bytes in prev. buffer:", tag);
				 for (int i = length_prev - 1; i > length_prev - 10; i--)
					 fprintf(stderr, "0x%.2" SCNx8, buf[i]);
				 fprintf(stderr, "\n");

				 exit(11);
		 }

		 //read length
		 unsigned char length_buf[4];
		 ReadToBuf(sockfd, length_buf, 4);

		 int length =
				 (length_buf[0] & 0xFF) << 0  |
         (length_buf[1] & 0xFF) << 8  |
         (length_buf[2] & 0xFF) << 16 |
         (length_buf[3] & 0xFF) << 24;

		 fprintf(stderr, "length=" " %.6" SCNi32 ", 0x%.2" SCNx8 ", 0x%.2" SCNx8 "%.2" SCNx8 "%.2" SCNx8 "%.2" SCNx8,
		 						 length, tag, length_buf[0], length_buf[1], length_buf[2], length_buf[3]);
		 if((length < 800000)||(length > 1)) {
				 unsigned tick = GetTickCount();
				 unsigned delta = tick - tick_prev;
				 tick_prev = tick;
				 printf("   %6"SCNu32", %"SCNi32"\n", delta, length);
		 } else {
				 fprintf(stderr, "wrong length=%"SCNu32"\n", length);
				 exit(11);
		 }

		 ReadToBuf(sockfd, buf, length);

		 length_prev = length;
   }

   return 0;
}
