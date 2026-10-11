import java.util.ArrayList;
import java.util.List;

/**
 * รวบรวมและคำนวณค่าที่ใช้วัดผลของการรันหนึ่งครั้ง
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ข้อกำหนดจากโจทย์ที่เกี่ยวกับคลาสนี้ (หัวข้อ 8):
 *   - Waiting Time, Turnaround Time, Throughput, Resource Wait Time
 *   - ต้องถูกอัปเดตจากหลาย Worker พร้อมกันได้อย่างปลอดภัย
 *   - ผลต้องสอดคล้องกับสมการตรวจสอบ:
 *       Turnaround = Waiting + workMs + Resource Wait + resourceMs
 *     ใช้สมการนี้ตรวจงานทีละชิ้นได้ว่าค่าไหนคำนวณผิด
 *
 * ข้อควรระวัง: ค่าเฉลี่ยของ Resource Wait ให้คิดเฉพาะงานที่ใช้ resource
 * ส่วนงานที่ resource = NONE ให้ถือว่า Resource Wait เป็น 0
 */
public class Statistics {

    // ✅ เก็บงานที่เสร็จแล้ว + ตัวนับ (ทุก method เป็น synchronized จึงปลอดภัยเมื่อหลาย Worker เรียกพร้อมกัน)
    private final Config config;
    private final List<Job> completedJobs = new ArrayList<>();
    private int running = 0;
    private long lastCompletionTime = 0;

    public Statistics(Config config) {
        this.config = config;
    }

    // ✅ Worker เริ่มงาน -> running + 1
    public synchronized void jobStarted() {
        running++;
    }

    // ✅ งานถูกยกเลิกกลางทาง (interrupt) -> running - 1
    public synchronized void jobAborted() {
        running--;
    }

    /** บันทึกว่างานชิ้นหนึ่งเสร็จแล้ว เรียกโดย Worker หลายตัวพร้อมกันได้ */
    public synchronized void recordCompletion(Job job) {
        running--;
        completedJobs.add(job);
        lastCompletionTime = Math.max(lastCompletionTime, job.completionTime);
    }

    /** จำนวนงานที่เสร็จแล้ว ใช้โดย Monitor และใช้ตรวจว่างานครบหรือยัง */
    public synchronized int completedCount() {
        return completedJobs.size();
    }

    // ✅ ให้ Monitor อ่าน running กับ completed พร้อมกัน {running, completed}
    public synchronized int[] snapshot() {
        return new int[] { running, completedJobs.size() };
    }

    // ✅ makespan = เวลาที่งานชิ้นสุดท้ายเสร็จ
    public synchronized long lastCompletionTime() {
        return lastCompletionTime;
    }

    /**
     * พิมพ์ตารางสรุปผลตอนจบโปรแกรม
     * อย่างน้อยต้องมี avg Waiting Time, avg Turnaround Time,
     * Throughput และ avg Resource Wait Time
     *
     * ตามหัวข้อ 14 ให้รายงานเวลาเป็นจำนวนเต็มหน่วย ms
     * และ Throughput อย่างน้อย 2 ตำแหน่งทศนิยม
     */
    public synchronized void printSummary(List<Job> allJobs, long makespanMs) {
        long sumWait = 0, sumTat = 0, sumRw = 0;
        int rwCount = 0;
        for (Job job : completedJobs) {
            sumWait += job.waitingTime();
            sumTat += job.turnaroundTime();
            if (job.resource != ResourceType.NONE) {   // ✅ avg RW คิดเฉพาะงานที่ใช้ resource
                sumRw += job.resourceWaitMs;
                rwCount++;
            }
        }
        int n = completedJobs.size();
        long avgWait = n == 0 ? 0 : Math.round((double) sumWait / n);
        long avgTat = n == 0 ? 0 : Math.round((double) sumTat / n);
        long avgRw = rwCount == 0 ? 0 : Math.round((double) sumRw / rwCount);
        double throughput = makespanMs == 0 ? 0 : n / (makespanMs / 1000.0);

        System.out.println();
        System.out.println("========== SUMMARY ==========");
        // ✅ ค่าที่ใช้รัน (ใช้ดูว่าผลนี้เป็นแถวไหนของตารางข้อ 14)
        System.out.printf("Workload            : %s%n", config.workloadPath);
        System.out.printf("Policy              : %s%n", config.policy == Config.Policy.FCFS ? "FCFS" : "Priority");
        System.out.printf("Workers             : %d%n", config.workers);
        System.out.printf("Printer             : %d%n", config.printerPermits);
        System.out.printf("DB                  : %d%n", config.databasePermits);
        System.out.println("-----------------------------");
        // ✅ ผลลัพธ์ (เอาไปกรอกตาราง: avg WT / avg TAT / Throughput / avg RW)
        System.out.printf("Total jobs          : %d%n", allJobs.size());
        System.out.printf("Completed jobs      : %d%n", n);
        System.out.printf("Avg Waiting Time    : %d ms%n", avgWait);
        System.out.printf("Avg Turnaround      : %d ms%n", avgTat);
        System.out.printf("Avg Resource Wait   : %d ms%n", avgRw);
        System.out.printf("Throughput          : %.2f jobs/sec%n", throughput);
        System.out.printf("Makespan            : %d ms%n", makespanMs);
        System.out.println("=============================");
    }
}
