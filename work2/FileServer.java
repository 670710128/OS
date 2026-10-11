import java.io.*;
import java.net.*; //ระบุport
import java.nio.ByteBuffer; //เก็บข้อมูลที่อ่านหรืแเขียนผ่าน server
import java.nio.channels.*;
import java.nio.charset.StandardCharsets; //แปลงข้อความด้วย UTF-8
import java.nio.file.*;
import java.security.MessageDigest; //คำนวณ hash 
import java.util.concurrent.*; //จัดการ thread 

public class FileServer {
    private static final int BUFFER_SIZE = 64 * 1024; //ขนาด buffer 65,536 bytes 
    private final Path root; //เก็บตน.floderที่อนุญาตให้โหลด
    private final ExecutorService pool = Executors.newCachedThreadPool(); //เป็นตัวจัดการงานแบบหลาย Thread ของฝั่ง Server

    private FileServer(Path root) {
        this.root = root.toAbsolutePath().normalize(); //ทำให้ตนไฟล์สั้นกระชับขึ้น
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 //args รับค่าที่ผู้ใช้ส่งมาจากคำสั่งรันโปรแกรม
        ? Integer.parseInt(args[0]) : 5000; //args[0] คือหมายเลข Port โดยค่าเริ่มต้นคือ 5000
        Path root = Paths.get(
            args.length > 1 ? args[1] : "shared"); //args[1] คือโฟลเดอร์เก็บไฟล์ โดยค่าเริ่มต้นคือ shared
        Files.createDirectories(root); //สร้างโฟลเดอร์ให้หากยังไม่มี
        new FileServer(root).start(port); //เริ่มรอรับการเชื่อมต่อ
    }

    private void start(int port) throws IOException { //รอรับ Client
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(port)); //สร้าง Channel สำหรับรอรับการเชื่อมต่อ TCP จาก Clientและบอกระบบปฏิบัติการว่า Server ต้องการรับการเชื่อมต่อที่ Port ที่กำหนด
            System.out.println("FileServer listening on port " + port);
            System.out.println("Shared folder: " + root);
            while (true) {
                //วนรอรับการเชื่อมต่อ
                SocketChannel client = server.accept(); //รอclient เข้ามา
                pool.submit(() -> handle(client)); //ส่งงานให้ pool 
                //ออกจาก try server ปิดเอง
            }
        }
    }

    private void handle(SocketChannel client) {
        //ประมวลคำสั่งของ client
        try (SocketChannel socket = client) {
            socket.configureBlocking(true); //แบบ blocking
            String request = readLine(socket); //คำขอ
            if (request == null) return;
            String[] p = request.trim().split("\\s+"); //ตัดคำสั่งที่มีช้องว่างและแบ่งเป็นส่วนโดยใช้ช่องว่าง
            if (p.length == 0) {
                sendLine(socket, "ERROR BAD_REQUEST Empty request");
                return;
            }

            switch (p[0].toUpperCase()) {
                case "LIST" -> listFiles(socket); //แสดงรายชื่อไฟล์ที่ Server เปิดให้ดาวน์โหลด
                case "INFO" -> {
                    if (p.length != 2) {
                        //ส่งขนาดไฟล์และค่า SHA-256
                        sendLine(socket, "ERROR BAD_REQUEST Usage: INFO filename"); 
                        return;
                    }
                    Path file = resolveFile(p[1]); //ตรวจสอบไฟล์
                    if (file == null || !Files.isRegularFile(file)) {
                        //ดูว่าไฟล์มีอยู่จริงมั้ย
                        sendLine(socket, "ERROR NOT_FOUND File not found");
                        return;
                    }
                    sendLine(socket, "SIZE " + Files.size(file) + " " + sha256(file));
                }
                case "GET" -> {
                    //ส่งข้อมูลเฉพาะช่วงที่ Client ร้องขอ
                    if (p.length != 5) {
                        sendLine(socket, "ERROR BAD_REQUEST Usage: GET filename offset length mode"); //ตรวจสอบคำสั่ง
                        return;
                    }
                    Path file = resolveFile(p[1]); //ตรวจสอบไฟล์
                    if (file == null || !Files.isRegularFile(file)) {
                        sendLine(socket, "ERROR NOT_FOUND File not found");
                        return;
                    }
                    long offset, length;
                    try {
                        offset = Long.parseLong(p[2]); //แปลงจาก string เป็น long
                        length = Long.parseLong(p[3]);
                    } catch (NumberFormatException e) {
                        sendLine(socket, "ERROR BAD_RANGE Offset/length must be numbers");
                        return;
                    }
                    String mode = p[4].toLowerCase(); //mode
                    long size = Files.size(file);
                    if (offset < 0 || length < 0 || offset > size || length > size - offset) {
                        sendLine(socket, "ERROR BAD_RANGE Requested range is outside file");
                        return;
                    }
                    if (!mode.equals("traditional") && !mode.equals("nio")) {
                        sendLine(socket, "ERROR BAD_MODE Use traditional or nio");
                        return;
                    }
                    sendLine(socket, "OK " + length);
                    if (mode.equals("traditional")) {
                        sendTraditional(socket, file, offset, length);
                    } else {
                        sendNio(socket, file, offset, length);
                    }
                }
                default -> sendLine(socket, "ERROR UNKNOWN_COMMAND Unknown command");
            }
        } catch (Exception e) {
            System.err.println("Client handler: " + e.getMessage());
        }
    }

    private Path resolveFile(String name) {
        //ตรวจสอบชื่อและตำแหน่งไฟล์
        // Only accept a simple file name; prevent directory traversal.
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) return null;
        Path candidate = root.resolve(name).normalize(); //สร้างpathหลัก
        return candidate.startsWith(root) ? candidate : null;
    }

    private void listFiles(SocketChannel socket) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) sendLine(socket, "FILE " + p.getFileName() + " " + Files.size(p));
                //สร้าง directory สำหรับวนอ่านรายการในโฟลเดอร์
            }
        }
        sendLine(socket, "END");
    }

    private void sendTraditional(SocketChannel socket, Path file, long offset, long length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");//เปิดไฟล์ให้read
             OutputStream out = Channels.newOutputStream(socket)) {
            raf.seek(offset); //เลื่อนตนอ่านไฟล์
            byte[] buffer = new byte[BUFFER_SIZE]; //พนพักข้อมูล 
            long remaining = length;
            while (remaining > 0) {
                //วนอ่าน
                int n = raf.read(buffer, 0, (int)Math.min(buffer.length, remaining)); //เลือกค่าที่นเอยกว่าระหว่าง bufferrและbyteที่เหลือ
                if (n < 0) throw new EOFException("File changed during download"); //เช็คโหลด
                out.write(buffer, 0, n); //เขียนจาก buffer เริ่มที่ 0 ถึง n 
                remaining -= n; //ที่ต้องโหลด
            }
            out.flush(); //ขอให้ข้อมูลที่ค้างอยู่ใน Buffer  ถูกส่งต่อไปยังปลายทาง
        }
    }

    private void sendNio(SocketChannel socket, Path file, long offset, long length) throws IOException {
        try (FileChannel source = FileChannel.open(file, StandardOpenOption.READ)) {
            long position = offset; 
            long remaining = length;
            while (remaining > 0) {
                long n = source.transferTo(position, remaining, socket); //คำสั่งนี้ขอให้ FileChannel ถ่ายโอนข้อมูลจำนวนไม่เกิน remaining ไบต์ โดยเริ่มจาก position ไปยัง socket
                if (n <= 0) throw new EOFException("transferTo stopped before requested length");
                position += n;
                remaining -= n;
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        //คำนวณhash
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); //ใช้ sha256ในการคำนวณ
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = new byte[BUFFER_SIZE];
            int n; //เก็บไบต์ที่อ่านได้จริง 
            while ((n = in.read(b)) != -1) 
                //อ่าข้อมูลลงbuffer
                digest.update(b, 0, n); //ข้อมูลใน buff ตน 0 จำนวน n ไปใช้คำนวณ
        }
        StringBuilder sb = new StringBuilder(); //create หะพรืเ
        for (byte v : digest.digest()) 
            sb.append(String.format("%02x", v)); //แปลงเป้นฐาน 16 อย่างน้อย 2 หลัก
        return sb.toString(); //คืนค่า
    }

    static String readLine(SocketChannel ch) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); //สร้างพทเก็บข้อความ
        ByteBuffer one = ByteBuffer.allocate(1); //ขนาด 1 ไบต์
        while (true) {
            one.clear(); //เตรียมอ่านรอบใหม่
            int n = ch.read(one); // อ่านข้อมูลจาก Socket ลงใน Buffer
            if (n == -1) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8); //end
            if (n == 0) continue; //ไม่มีให้อ่าน
            byte b = one.array()[0];
            if (b == '\n') break;
            if (b != '\r') bytes.write(b);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    static void sendLine(SocketChannel ch, String line) throws IOException {
        ByteBuffer b = StandardCharsets.UTF_8.encode(line + "\n");
        while (b.hasRemaining()) ch.write(b);
    }
}
