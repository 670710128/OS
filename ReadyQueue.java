import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * คิวงานที่พร้อมถูกหยิบไปทำ
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * สิ่งที่คลาสนี้ต้องทำได้:
 *   - เก็บงานที่รอ Worker อยู่
 *   - หยิบงานถัดไปตามนโยบายที่เลือก (FCFS หรือ Priority)
 *   - ถูกเรียกจากหลาย Thread พร้อมกันได้อย่างปลอดภัย
 *
 * ข้อกำหนดจากโจทย์ที่เกี่ยวกับคลาสนี้:
 *   - หัวข้อ 4: priority = 1 สูงสุด เมื่อเท่ากันต้องมีกติกาตัดสินลำดับ (tie-break)
 *     ที่ตัดสินจากข้อมูลของ Job ไม่ขึ้นกับว่า Thread ใดเข้าถึงคิวก่อน
 *   - หัวข้อ 7: ห้ามวนลูปเช็กแบบกิน CPU (busy waiting) — Worker ที่ไม่มีงานทำ
 *     ต้องถูกพักไว้ ไม่ใช่วนถามซ้ำ ๆ
 *
 * จะออกแบบเป็นคลาสเดียวที่รับนโยบายเข้ามา หรือแยกเป็นสองคลาส
 * หรือใช้โครงสร้างข้อมูลสำเร็จรูปของ Java ก็ได้ ขอให้อธิบายเหตุผลได้ใน Demo
 */
public class ReadyQueue {

    // ✅ เก็บนโยบาย (Config.Policy) และโครงสร้างข้อมูลที่ใช้เก็บงาน
    private final Config.Policy policy;
    private final PriorityQueue<Job> queue;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition hasJob = lock.newCondition();
    private long nextSequence = 0;
    private boolean closed = false;

    public ReadyQueue(Config.Policy policy) {
        // ✅ FCFS: เข้าคิวก่อนได้ก่อน
        // ✅ Priority: priority น้อยก่อน -> เท่ากันดู arrivalMs -> เท่ากันดูลำดับในไฟล์ (tie-break)
        this.policy = policy;
        Comparator<Job> order = (policy == Config.Policy.FCFS)
                ? Comparator.comparingLong((Job j) -> j.readySequence)
                : Comparator.comparingInt((Job j) -> j.priority)
                            .thenComparingLong(j -> j.arrivalMs)
                            .thenComparingInt(j -> j.sequence);
        this.queue = new PriorityQueue<>(order);
    }

    /** ใส่งานเข้าคิว เรียกโดย Scheduler Thread */
    public void add(Job job) {
        lock.lock();
        try {
            job.readySequence = nextSequence++;
            queue.add(job);
            hasJob.signal();   // ✅ ปลุก Worker ที่รออยู่
        } finally {
            lock.unlock();
        }
    }

    /**
     * หยิบงานถัดไปตามนโยบาย เรียกโดย Worker Thread
     *
     * ถ้ายังไม่มีงาน ต้องรอโดยไม่กิน CPU
     * ต้องคิดด้วยว่าจะบอก Worker อย่างไรเมื่อไม่มีงานเหลือแล้วและควรหยุดทำงาน
     */
    public Job take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty() && !closed) {
                hasJob.await();   // ✅ พักรอ ไม่กิน CPU
            }
            return queue.poll();  // ✅ คืน null = ปิดคิวแล้วและไม่มีงานเหลือ -> Worker หยุด
        } finally {
            lock.unlock();
        }
    }

    // ✅ Scheduler เรียกเมื่อส่งงานครบแล้ว -> ปลุก Worker ทุกตัว
    public void close() {
        lock.lock();
        try {
            closed = true;
            hasJob.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** จำนวนงานที่รออยู่ตอนนี้ ใช้โดย Monitor — ต้องอ่านได้อย่างปลอดภัย */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public Config.Policy policy() {
        return policy;
    }
}
