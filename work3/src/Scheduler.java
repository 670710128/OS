import java.util.concurrent.BlockingQueue;

/**
 * รับงานจาก JobGenerator แล้วจัดเข้า Ready Queue
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ข้อกำหนดจากโจทย์ (หัวข้อ 2 และ 4):
 *   - Scheduler เป็น Thread บังคับ ห้ามให้ JobGenerator ใส่งานลง Ready Queue โดยตรง
 *   - รับผิดชอบการจัดลำดับตามนโยบาย FCFS หรือ Priority
 *
 * ข้อควรคิด:
 *   - Scheduler รับงานจาก JobGenerator ผ่านอะไร และรอโดยไม่กิน CPU อย่างไร
 *   - เมื่อ JobGenerator ปล่อยงานครบแล้ว Scheduler รู้ได้อย่างไรว่าควรหยุด
 */
public class Scheduler extends Thread {

    // ✅ เก็บช่องทางรับงานจาก JobGenerator, ReadyQueue ปลายทาง และ logger
    private final BlockingQueue<Job> arrivalQueue;
    private final ReadyQueue readyQueue;
    private final ProjectLogger logger;

    public Scheduler(BlockingQueue<Job> arrivalQueue, ReadyQueue readyQueue, ProjectLogger logger) {
        super("scheduler");
        this.arrivalQueue = arrivalQueue;
        this.readyQueue = readyQueue;
        this.logger = logger;
    }

    // ✅ วนรับงานเข้ามาแล้วใส่ ReadyQueue จนกว่าจะได้รับสัญญาณให้หยุด
    @Override
    public void run() {
        try {
            while (true) {
                Job job = arrivalQueue.take();   // ✅ รอแบบไม่กิน CPU
                if (job == JobGenerator.END) {
                    break;                       // ✅ ได้ poison pill = ส่งงานครบแล้ว
                }
                logger.systemEvent("JOB_READY job=" + job.id + " priority=" + job.priority);
                readyQueue.add(job);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            readyQueue.close();   // ✅ บอก Worker ว่าไม่มีงานใหม่แล้ว
        }
    }
}
