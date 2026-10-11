# Multi-threaded File Download Client/Server (Java)

## 1) Requirements
- JDK 17 or newer (the code uses Java switch-arrow syntax).
- No external libraries are required.

## 2) Compile
Open a terminal in this folder:

```bash
javac FileServer.java FileClient.java
```

## 3) Prepare a file for download
Create a folder named `shared` beside the `.java` files and put a test file inside it.
For example:

```text
multithread_file_download/
  FileServer.java
  FileClient.java
  shared/
    sample.zip
```

## 4) Start the server
Terminal 1:

```bash
java FileServer 5000 shared
```

The server listens on TCP port 5000 and serves files from `shared`.

## 5) Run the client
Terminal 2 (10 workers, Traditional I/O):

```bash
java FileClient 127.0.0.1 5000 sample.zip downloaded-traditional.zip 10 traditional
```

10 workers, NIO transfer:

```bash
java FileClient 127.0.0.1 5000 sample.zip downloaded-nio.zip 10 nio
```

For the required 1-worker tests, change `10` to `1`:

```bash
java FileClient 127.0.0.1 5000 sample.zip downloaded-1w-traditional.zip 1 traditional
java FileClient 127.0.0.1 5000 sample.zip downloaded-1w-nio.zip 1 nio
```

## 6) Protocol
Each request uses a separate TCP connection.

- `LIST` -> zero or more `FILE <filename> <size>` lines, then `END`
- `INFO <filename>` -> `SIZE <bytes> <sha256>` or `ERROR <code> <message>`
- `GET <filename> <offset> <length> <mode>` -> `OK <length>`, then exactly `<length>` raw bytes
- `mode` is `traditional` or `nio`

The client obtains the file size and SHA-256, divides the file into non-overlapping byte ranges, and starts one task/connection per worker. The final worker receives any remainder bytes. Each task writes to its own absolute position in the output FileChannel.

## 7) Experiment procedure
Run every combination at least three times, using the same source file and machine:

| Method | Workers | Run 1 (s) | Run 2 (s) | Run 3 (s) | Average (s) | Throughput (MB/s) |
|---|---:|---:|---:|---:|---:|---:|
| Traditional | 1 | | | | | |
| Traditional | 10 | | | | | |
| NIO | 1 | | | | | |
| NIO | 10 | | | | | |

The client prints elapsed time, throughput, downloaded size, and SHA-256 check for each run.
Throughput is calculated as file size in MiB divided by elapsed seconds.

## 8) Notes for the report
- 10 workers may not be 10 times faster because workers share CPU, disk, memory bandwidth, and network capacity. Connection setup and scheduling also add overhead.
- NIO `transferTo`/`transferFrom` can reduce copying in some environments, but it is not guaranteed to win every time. Operating system, JVM, file system, socket implementation, and caching affect results.
- When using localhost, loopback networking and OS page cache/storage cache affect measurements. State clearly that the test is not a measurement of a real physical network.
- Do not invent experiment numbers. Record the actual output from your own runs.
