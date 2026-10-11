import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest; //คำนวณ SHA-256
import java.util.*; //list ,arraylist
import java.util.concurrent.*; //threadpool

public class FileClient {
    private static final int BUFFER_SIZE = 64 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equalsIgnoreCase("LIST")) {
        String host = args.length > 1 ? args[1] : "127.0.0.1";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 5000;

        listFiles(host, port);
        return;
    }
        if (args.length < 3) {
            usage();
            return;
        }
        String host = args[0]; //ip host
        int port = Integer.parseInt(args[1]); //port
        String filename = args[2]; //file name 
        Path output = Paths.get(args.length > 3 ? args[3] : filename);
        int workers = args.length > 4 ? Integer.parseInt(args[4]) : 10; //ถ้าไม่ได้กำหนดใช้ 10 
        String mode = args.length > 5 ? args[5].toLowerCase() : "traditional"; //ถ้าไม่ได้กำหนดใช้ 10  

        if (workers != 1 && workers != 10) {
            System.err.println("Workers must be 1 or 10 for this assignment.");
            return; //ตรวจสอบบ
        }
        if (!mode.equals("traditional") && !mode.equals("nio")) {
            System.err.println("Mode must be traditional or nio.");
            return; //ตรวจสอบบ
        }
        Info info = getInfo(host, port, filename); //จากserver
        System.out.println("Remote size : " + info.size + " bytes");
        System.out.println("Remote SHA-256: " + info.sha256);

        Path parent = output.toAbsolutePath().getParent(); //สร้างโฟลเดอร์ปลายทาง
        if (parent != null) Files.createDirectories(parent);
        try (FileChannel destination = FileChannel.open(
            output,
            StandardOpenOption.CREATE, //lสร้าง
            StandardOpenOption.WRITE, //เขียน //ล้างข้อมูลเก่า
            StandardOpenOption.TRUNCATE_EXISTING )) {
            destination.truncate(info.size); //กำหนดขนาดไฟล์
            ExecutorService executor = Executors.newFixedThreadPool(workers); //createtreadpool
            List<Future<?>> tasks = new ArrayList<>();
            long baseLength = info.size / workers; //แบ่งงาน
            long startTime = System.nanoTime();

            for (int i = 0; i < workers; i++) {
                final int workerId = i; //creat worker
                final long offset = i * baseLength; //จุดเริ่มต้นของส่วนที่ Worker จะดาวน์โหลด
                final long length = (i == workers - 1) ? info.size - offset : baseLength; //จำนวนข้อมูลที่ Worker ต้องดาวน์โหลด
                tasks.add(executor.submit(() -> downloadRange(
                        host, 
                        port, 
                        filename, 
                        offset, 
                        length, 
                        mode, 
                        destination, 
                        workerId)));
            }

            executor.shutdown(); //ปิดรับงานใหม่
            try {
                for (Future<?> task : tasks) 
                    task.get(); //รอจน Worker ตัวนั้นทำงานเสร็จ
            } catch (ExecutionException e) {
                executor.shutdownNow();
                throw new RuntimeException("A download worker failed", e.getCause()); //แจ้ง
            }

            long elapsed = System.nanoTime() - startTime;
            double seconds = elapsed / 1_000_000_000.0; //แปลงเป็นวินาที 
            double mb = info.size / (1024.0 * 1024.0);
            double throughput = seconds > 0 ? mb / seconds : 0; //Throughput

            long actualSize = Files.size(output); //ตรวจขนาดไฟล์
            String actualHash = sha256(output);
            System.out.printf(Locale.US, "%nDownload complete: %s%n", output.toAbsolutePath());
            System.out.println("Workers       : " + workers);
            System.out.println("Mode          : " + mode);
            System.out.printf(Locale.US, "Elapsed time  : %.3f seconds%n", seconds);
            System.out.printf(Locale.US, "Throughput    : %.2f MB/s%n", throughput);
            System.out.println("Downloaded size: " + actualSize + " bytes");
            System.out.println("Downloaded SHA-256: " + actualHash);
            System.out.println("Size check    : " + (actualSize == info.size ? "PASS" : "FAIL"));
            System.out.println("Hash check    : " + (actualHash.equals(info.sha256) ? "PASS" : "FAIL"));
        }
        
    }

    private static Info getInfo(String host, int port, String filename) throws Exception {
        try (SocketChannel socket = connect(host, port)) {
            sendLine(socket, "INFO " + filename);
            String response = readLine(socket);
            if (response == null || response.startsWith("ERROR")) throw new IOException("Server: " + response);
            String[] p = response.split("\\s+"); //แยกข้อมูล Response
            if (p.length < 3 || !p[0].equals("SIZE")) throw new IOException("Unexpected INFO response: " + response);
            return new Info(
                Long.parseLong(p[1]), //size
                 p[2] //sha
                );
        }
    }

    private static void downloadRange(String host, int port, String filename, long offset,
                                      long length, String mode, FileChannel destination, int id) {
        try (SocketChannel socket = connect(host, port)) {
            sendLine(socket, "GET " + filename + " " + offset + " " + length + " " + mode); //ขอชื่อ 
            String response = readLine(socket);
            if (response == null || response.startsWith("ERROR")) throw new IOException("Server: " + response); //ตรวจ
            String[] p = response.split("\\s+");
            if (p.length != 2 || !p[0].equals("OK") || Long.parseLong(p[1]) != length)
                throw new IOException("Unexpected GET response: " + response);

            if (mode.equals("traditional")) {
                InputStream in = Channels.newInputStream(socket);
                byte[] buffer = new byte[BUFFER_SIZE];
                long position = offset;
                long remaining = length;
                while (remaining > 0) {
                    int n = in.read(buffer, 0, (int)Math.min(buffer.length, remaining)); //อ่านข้อมูลเข้ามาใน buffer
                    if (n < 0) throw new EOFException("Worker " + id + " received incomplete data");
                    ByteBuffer data = ByteBuffer.wrap(buffer, 0, n);
                    while (data.hasRemaining()) {
                        int written = destination.write(data, position); //เขียนลงในไฟล์
                        position += written;
                    }
                    remaining -= n;
                }
            } else {
                long position = offset;
                long remaining = length;
                while (remaining > 0) {
                    long n = destination.transferFrom(socket, position, remaining);
                    if (n <= 0) throw new EOFException("Worker " + id + " received incomplete data");
                    position += n;
                    remaining -= n;
                }
            }
            System.out.println("Worker " + id + " finished: offset=" + offset + ", length=" + length);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    private static SocketChannel connect(String host, int port) throws IOException {
        SocketChannel ch = SocketChannel.open(); //สร้าง
        ch.configureBlocking(true); //Blocking Mode
        ch.connect(new InetSocketAddress(host, port)); //เชื่อมไปserver
        return ch;
    }

    private static String readLine(SocketChannel ch) throws IOException {
        //อ่านข้อความจากserver
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteBuffer one = ByteBuffer.allocate(1); //สร้างbytebuffer ๅ byte
        while (true) {
            one.clear();//clear wait for new info
            int n = ch.read(one); //clear wait for new info
            if (n == -1) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
            if (n == 0) continue;
            byte b = one.array()[0];
            if (b == '\n') break;
            if (b != '\r') bytes.write(b);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void sendLine(SocketChannel ch, String line) throws IOException {
        //ส่งคำสั่ง
        ByteBuffer b = StandardCharsets.UTF_8.encode(line + "\n");
        while (b.hasRemaining()) ch.write(b);
    }

    private static String sha256(Path file) throws Exception {
        //ตรวจสอบความถูกต้องของไฟล์
        //SHA-256 เป็นฟังก์ชัน Hash ที่รับข้อมูลขนาดต่าง ๆ แล้วสร้างค่า Hash ขนาด 256 บิต หรือ 32 bytes ซึ่งมักแสดงเป็นเลขฐาน 16 จำนวน 64 ตัวอักษร
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = new byte[BUFFER_SIZE]; //พทพักข้อมูล
            int n; //เก็บจำนวน bytes ที่อ่านได้จริง
            while ((n = in.read(b)) != -1) digest.update(b, 0, n); //นำข้อมูลใน b ตั้งแต่ 0 - nไปคำนวณ
        }
        StringBuilder sb = new StringBuilder();
        for (byte v : digest.digest()) sb.append(String.format("%02x", v)); //แปลง bytes เป็น ฐาน16 โดดยใช้ตัวเล็กและเติมศูนย์ด้านหา้ให้ครบ2
        return sb.toString();
    }

    private static void listFiles(String host, int port) throws Exception {
    try (SocketChannel socket = connect(host, port)) {
        sendLine(socket, "LIST");

        while (true) {
            String response = readLine(socket);

            if (response == null) {
                throw new IOException("Server disconnected");
            }

            if (response.equals("END")) {
                System.out.println("End of file list.");
                break;
            }

            if (response.startsWith("ERROR")) {
                System.out.println("Server: " + response);
                break;
            }

            System.out.println(response);
        }
    }
}
    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  java FileClient <host> <port> <filename> [output] [workers:1|10] [traditional|nio]");
        System.out.println("Example:");
        System.out.println("  java FileClient 127.0.0.1 5000 sample.zip downloaded.zip 10 traditional");
        System.out.println("  java FileClient 127.0.0.1 5000 sample.zip downloaded-nio.zip 10 nio");
    }

    private static class Info {
        final long size;
        final String sha256;
        Info(long size, String sha256) {
             this.size = size;
              this.sha256 = sha256;
             }
    }
}
