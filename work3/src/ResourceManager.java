import java.util.concurrent.Semaphore;

/**
 * ควบคุมสิทธิ์การใช้ทรัพยากรร่วมของทั้งระบบ
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ข้อกำหนดจากโจทย์ที่เกี่ยวกับคลาสนี้:
 *   - หัวข้อ 5: ใช้ Semaphore ควบคุม PRINTER และ DATABASE
 *     จำนวน permit มาจาก command line (Config)
 *     ในส่วนบังคับให้สร้าง Semaphore แบบ fair = true
 *   - Worker ทุกตัวต้องใช้ ResourceManager object เดียวกัน
 *   - หัวข้อ 7: permit ต้องไม่สูญหายหรือค้าง แม้เกิด exception
 *     หรือถูก interrupt ระหว่างถือ resource
 *
 * คำถามที่จะถูกถามใน Demo:
 *   - ทำไมต้อง fair = true และถ้าเปลี่ยนเป็น false จะเกิดอะไรขึ้น
 *   - ถ้า Thread ถูก interrupt หลัง acquire สำเร็จแต่ก่อน release
 *     โค้ดของกลุ่มยังคืน permit ได้หรือไม่
 */
public class ResourceManager {

    // ✅ เก็บ Semaphore ของ PRINTER และ DATABASE
    private final Semaphore printer;
    private final Semaphore database;
    private final int printerPermits;
    private final int databasePermits;

    public ResourceManager(int printerPermits, int databasePermits) {
        this.printerPermits = printerPermits;
        this.databasePermits = databasePermits;
        this.printer = new Semaphore(printerPermits, true);    // ✅ fair = true
        this.database = new Semaphore(databasePermits, true);
    }

    /** ขอสิทธิ์ใช้ทรัพยากร จะรอจนกว่าจะได้ */
    public void acquire(ResourceType type) throws InterruptedException {
        if (type == ResourceType.PRINTER) printer.acquire();
        if (type == ResourceType.DATABASE) database.acquire();
    }

    /** คืนสิทธิ์ใช้ทรัพยากร */
    public void release(ResourceType type) {
        if (type == ResourceType.PRINTER) printer.release();
        if (type == ResourceType.DATABASE) database.release();
    }

    /**
     * ข้อความสั้น ๆ บอกสถานะการใช้ทรัพยากร สำหรับส่งให้ ProjectLogger.monitor()
     * เช่น "printer=1/1 database=0/2"
     */
    public String status() {
        int printerUsed = printerPermits - printer.availablePermits();
        int databaseUsed = databasePermits - database.availablePermits();
        return "printer=" + printerUsed + "/" + printerPermits
                + " database=" + databaseUsed + "/" + databasePermits;
    }
}
