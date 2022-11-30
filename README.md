# UDP-Server-Implementation
Implement a UDP server from a TCP implementation of a httpc client and a httpf server to handle get request to list files in directory or content of specific files, or post request to create file

 re-implement the HTTP client and the HTTP remote file manager of
Assignments #1 and #2 respectively using UDP protocol. In the previous assignments, you leveraged the
TCP protocol for your implementation to guarantee packet transmission over unreliable network links.
Because you are going to use UDP protocol which does not guarantee a reliable transfer, you need to
ensure reliability by implementing a specific instance of the Automatic-Repeat-Request (ARQ) protocol
called: Selective Repeat ARQ / Selective Reject ARQ. Before starting on this Lab, we encourage you to
review the programming samples and the associated course materials.
Outline
The following is a summary of the main tasks of the Assignment:
1. Study the simulation network infrastructure and message structure.
2. Replace TCP by UDP in your HTTP library in both the client and server.
3. Implement “Selective-Repeat” flow control to enable a reliable transport.
4. (Optional) Support multiple clients at the server.
