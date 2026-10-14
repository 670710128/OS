import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * ปล่อยงานเข้าสู่ระบบตามเวลา arrivalMs ของแต่ละ Job
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * หน้าที่ (หัวข้อ 3 ของโจทย์):
 *   - รอจนถึงเวลา arrivalMs ของแต่ละงาน แล้วส่งงานต่อไปยัง Scheduler
 *   - บันทึกเวลาที่งานเข้าสู่ระบบ "จริง" ลงใน Job
 *     (อาจไม่ตรงกับ arrivalMs เป๊ะ เพราะ Thread ถูกปลุกช้าได้)
 *   - เรียก logger.jobArrived(job) ทุกครั้งที่ปล่อยงาน
 *
 * ข้อควรคิด:
 *   - รายการงานที่ได้จาก WorkloadLoader เรียงตามลำดับในไฟล์ ไม่ได้เรียงตามเวลา
 *   - เมื่อปล่อยงานครบทุกชิ้นแล้ว ต้องมีวิธีบอกระบบว่า "จะไม่มีงานเข้ามาอีก"
 *     ดู TODO เรื่องการปิดระบบใน Main
 */
public class JobGenerator extends Thread {

    // ✅ poison pill: บอก Scheduler ว่าส่งงานครบแล้ว
    public static final Job END = new Job("END", 0, 0, 0, ResourceType.NONE, 0, -1);

    // ✅ เก็บรายการงาน, ช่องทางส่งงานไปยัง Scheduler (arrivalQueue) และ logger
    private final List<Job> jobs;
    private final BlockingQueue<Job> arrivalQueue;
    private final ProjectLogger logger;

    public JobGenerator(List<Job> jobs, BlockingQueue<Job> arrivalQueue, ProjectLogger logger) {
        super("generator");
        this.jobs = new ArrayList<>(jobs);
        this.jobs.sort(Comparator.comparingLong((Job j) -> j.arrivalMs)
                                 .thenComparingInt(j -> j.sequence));   // ✅ เรียงตามเวลาเข้า
        this.arrivalQueue = arrivalQueue;
        this.logger = logger;
    }

    // ✅ วนปล่อยงานตามเวลา แล้วแจ้งเมื่อปล่อยครบ
    @Override
    public void run() {
        try {
            for (Job job : jobs) {
                long delay = job.arrivalMs - logger.now();
                if (delay > 0) {
                    Thread.sleep(delay);   // ✅ รอถึงเวลา arrivalMs
                }
                job.actualArrivalTime = logger.now();
                logger.jobArrived(job);
                arrivalQueue.put(job);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            arrivalQueue.offer(END);   // ✅ ส่ง poison pill เสมอ ไม่งั้นระบบค้าง
        }
    }
}
