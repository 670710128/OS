/**
 * Thread ที่ดึงงานจาก Ready Queue ไปทำจนเสร็จ
 *
 * ===== ไฟล์นี้เป็นโครงเปล่า นักศึกษาต้องเขียนเอง =====
 *
 * ลำดับการทำงานของ Job หนึ่งชิ้น บังคับตามหัวข้อ 6 ของโจทย์:
 *   1. รับงานจาก Ready Queue แล้วบันทึกเวลาเริ่ม
 *   2. จำลองงานหลักด้วย Thread.sleep(job.workMs)
 *   3. ถ้า job.resource != NONE ให้บันทึกเวลาเริ่มรอ แล้ว acquire
 *   4. จำลองการถือครองด้วย Thread.sleep(job.resourceMs)
 *   5. release แล้วบันทึกเวลาจบ
 *
 * ห้ามสลับขั้นที่ 2 กับ 3 เพราะจะทำให้ผลของทุกกลุ่มเทียบกันไม่ได้
 *
 * จุดที่มักพลาด:
 *   - ถ้า exception หรือ interrupt เกิดขึ้นหลัง acquire แต่ก่อน release
 *     permit จะค้างถาวรและระบบจะแขวน ต้องออกแบบให้คืนได้เสมอ
 *   - Worker ต้องหยุดเองได้เมื่อไม่มีงานเหลือแล้ว ไม่ใช่วนรอตลอดไป
 */
public class Worker extends Thread {

    // ✅ เก็บ ReadyQueue, ResourceManager, Statistics และ logger
    private final ReadyQueue readyQueue;
    private final ResourceManager resources;
    private final Statistics statistics;
    private final ProjectLogger logger;

    public Worker(String name, ReadyQueue readyQueue, ResourceManager resources,
                  Statistics statistics, ProjectLogger logger) {
        super(name);
        this.readyQueue = readyQueue;
        this.resources = resources;
        this.statistics = statistics;
        this.logger = logger;
    }

    // ✅ วนรับงานและเรียก processJob จนกว่าจะได้รับสัญญาณให้หยุด
    @Override
    public void run() {
        while (true) {
            Job job;
            try {
                job = readyQueue.take();
            } catch (InterruptedException e) {
                continue;            // ✅ ถูก interrupt ตอนรอคิว -> ยังไม่ถืองาน รอต่อได้
            }
            if (job == null) {
                break;               // ✅ คิวปิดและไม่มีงานเหลือ -> หยุด
            }
            try {
                processJob(job);
            } catch (InterruptedException e) {
                statistics.jobAborted();   // ✅ ถูก interrupt กลางงาน -> ยกเลิกงานนี้ ทำงานถัดไปต่อ
                logger.systemEvent("JOB_ABORTED job=" + job.id);
            }
        }
    }

    /** ทำงานหนึ่งชิ้นให้จบตามลำดับ 5 ขั้นด้านบน */
    private void processJob(Job job) throws InterruptedException {
        // ✅ 1. บันทึกเวลาเริ่มทำงาน
        job.startTime = logger.now();
        statistics.jobStarted();
        logger.jobStarted(job);

        // ✅ 2. จำลองงานหลัก
        Thread.sleep(job.workMs);
        logger.workFinished(job);

        if (job.resource != ResourceType.NONE) {
            // ✅ 3. บันทึกเวลาเริ่มรอ แล้ว acquire
            job.resourceWaitStartTime = logger.now();
            logger.resourceWaitStarted(job);
            resources.acquire(job.resource);
            try {
                job.resourceWaitMs = logger.now() - job.resourceWaitStartTime;
                logger.resourceAcquired(job, job.resourceWaitMs);

                // ✅ 4. จำลองการถือครอง resource
                Thread.sleep(job.resourceMs);
            } finally {
                // ✅ 5. คืน resource เสมอ แม้ถูก interrupt (log ก่อน release ให้ลำดับใน log ถูก)
                logger.resourceReleased(job);
                resources.release(job.resource);
            }
        }

        // ✅ บันทึกเวลาจบ และอัปเดต Statistics
        job.completionTime = logger.now();
        logger.jobCompleted(job);
        statistics.recordCompletion(job);
    }
}
