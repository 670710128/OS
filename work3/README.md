# Mini Job Scheduler & Shared Resource Manager

517-312 Operating Systems — Term Project

## วิธี compile และรัน

ต้องใช้ Java 11 ขึ้นไป รันจากโฟลเดอร์ `src`

```bash
cd src
javac -encoding UTF-8 *.java
java Main ../workloads/jobs_standard.csv priority 3 1 2
```

## Command-line arguments

```
java Main <workload.csv> <fcfs|priority> <workers> <printerPermits> <databasePermits>
```

| ลำดับ | ค่า | ความหมาย |
|---|---|---|
| 1 | `workload.csv` | ไฟล์ชุดงาน |
| 2 | `fcfs` หรือ `priority` | นโยบายจัดลำดับงาน |
| 3 | `workers` | จำนวน Worker Thread (≥ 1) |
| 4 | `printerPermits` | จำนวน permit ของ PRINTER (≥ 1) |
| 5 | `databasePermits` | จำนวน permit ของ DATABASE (≥ 1) |

ต้องใส่ครบ 5 ค่า ถ้าไม่ครบหรือค่าผิด โปรแกรมจะแสดง Usage แล้วจบทันที (ไม่มีค่า default)

## การรันชุดทดลองและเก็บ log

เก็บ log ลงไฟล์ด้วยการ redirect (รันจากโฟลเดอร์ `src`) ชุดที่ใช้ตามหัวข้อ 14:

```bash
java Main ../workloads/jobs_standard.csv fcfs 3 1 2     > ../logs/1_standard_fcfs_w3_p1_d2.log
java Main ../workloads/jobs_standard.csv priority 3 1 2 > ../logs/2_standard_priority_w3_p1_d2.log
java Main ../workloads/jobs_standard.csv priority 1 1 2 > ../logs/3_standard_priority_w1_p1_d2.log
java Main ../workloads/jobs_standard.csv priority 5 1 2 > ../logs/4_standard_priority_w5_p1_d2.log
java Main ../workloads/jobs_printer.csv priority 3 1 2  > ../logs/5_printer_priority_w3_p1_d2.log
java Main ../workloads/jobs_printer.csv priority 3 2 2  > ../logs/6_printer_priority_w3_p2_d2.log
java Main ../workloads/jobs_standard.csv priority 3 1 2 > ../logs/7_standard_priority_w3_p1_d2_repeat.log
```

แถว 7 คือรันแถว 2 ซ้ำเพื่อดู nondeterminism ผลของแต่ละชุดดูได้จาก SUMMARY ท้ายไฟล์ log

## การออกแบบโดยย่อ

- JobGenerator → `arrivalQueue` (BlockingQueue) → Scheduler → ReadyQueue → Worker
- ReadyQueue ใช้ PriorityQueue + ReentrantLock + Condition (Worker ที่ไม่มีงานจะ `await()` ไม่กิน CPU)
- Priority: เลข priority น้อยก่อน → ถ้าเท่ากันดู `arrivalMs` → ถ้ายังเท่ากันดูลำดับในไฟล์ (tie-break)
- ResourceManager ใช้ `Semaphore(permits, fair = true)` และคืน permit ใน `finally` เสมอ
- ปิดระบบด้วย poison pill: Generator ส่ง END → Scheduler ปิด ReadyQueue → Worker ทำงานที่เหลือจนหมดแล้วหยุดเอง → main `join()` ทุก Thread แล้วสั่งหยุด Monitor

## ข้อจำกัดที่ควรรู้

**1. ผลแต่ละรอบต่างกันเล็กน้อย (nondeterminism)**
รันด้วยค่าเดิม เวลาใน log และค่าเฉลี่ยอาจต่างกันไม่กี่ ms เพราะ OS เป็นคนตัดสินว่าจะให้ Thread ไหนได้ทำงานก่อน และ JVM อาจหยุดสั้น ๆ เพื่อ JIT หรือ GC
แต่ลำดับ START ของงานจะเหมือนเดิมทุกรอบ เพราะกติกาจัดลำดับใช้ข้อมูลของ Job อย่างเดียว ไม่ขึ้นกับว่า Thread ไหนมาถึงคิวก่อน

**2. เวลาที่วัดได้มากกว่าค่าในไฟล์เล็กน้อย**
งานและการถือ resource จำลองด้วย `Thread.sleep(workMs)` และ `Thread.sleep(resourceMs)` ซึ่งรับประกันแค่ว่าจะหลับ "อย่างน้อย" เท่าที่กำหนด Thread อาจตื่นช้ากว่านั้นไม่กี่ ms
จึงทำให้สมการ TAT = WT + workMs + RW + resourceMs คลาดได้ไม่กี่ ms ต่องาน และ JobGenerator ก็อาจปล่อยงานช้ากว่า arrivalMs เล็กน้อย (ค่าที่ใช้คำนวณคือเวลาเข้าระบบจริง)

**3. ไม่แย่ง CPU กลางคัน และใช้ resource ได้ชนิดเดียว**
FCFS และ Priority เป็นแบบ non-preemptive เมื่อ Worker รับงานไปแล้วจะทำจนจบ งาน priority สูงที่มาทีหลังต้องรอ Worker ว่างก่อน
Job หนึ่งใช้ resource ได้ไม่เกิน 1 ชนิด (NONE / PRINTER / DATABASE) ตามส่วนบังคับ จึงไม่ได้รองรับกรณีถือหลาย resource พร้อมกันหรือป้องกัน deadlock
ไม่ได้ทำส่วน Bonus (Aging, MLFQ ฯลฯ) จึงอาจเกิด starvation กับงาน priority ต่ำได้ถ้ามีงาน priority สูงเข้ามาเรื่อย ๆ

**4. Worker ถูก interrupt = ยกเลิกงานนั้น**
ถ้า Worker ถูก interrupt ระหว่างทำงาน (เช่นตอนรอ Semaphore หรือตอนถือ resource) งานนั้นจะถูกยกเลิกและบันทึก `JOB_ABORTED`
permit จะถูกคืนเสมอเพราะ release อยู่ใน `finally` และ Worker จะไปทำงานถัดไปต่อ ระบบจึงไม่ค้าง
แต่งานที่ถูกยกเลิกจะไม่นับเป็นงานที่เสร็จ ทำให้ `completed` น้อยกว่าจำนวนงานทั้งหมด และไม่ถูกนำไปคิดค่าเฉลี่ย

**5. สถานะ READY ใน log อยู่ในรูปแบบ SYSTEM**
`ProjectLogger` ที่อาจารย์แจกไม่มี method สำหรับเหตุการณ์ READY และเป็นไฟล์ที่ห้ามแก้ Scheduler จึงบันทึกผ่าน `systemEvent()` แทน
ใน log จะเห็นเป็น `[scheduler ] SYSTEM   JOB_READY job=J01 priority=5` ซึ่งยังมีชื่อ Thread และเวลาครบ แต่ชื่อเหตุการณ์ไม่ได้อยู่ในคอลัมน์เดียวกับ JOB_ARRIVED / JOB_STARTED

**6. ภาษาไทยอาจแสดงเป็น `???` บน Windows**
ข้อความภาษาไทยใน log (เช่น "โหลดงานได้ 10 ชิ้น" หรือข้อความ Usage) อาจแสดงผิดใน Command Prompt ที่ไม่ได้ตั้ง encoding เป็น UTF-8 ซึ่งเป็นเรื่องของหน้าจอ ไม่ใช่บั๊กของโปรแกรม
แก้ได้โดยสั่ง `chcp 65001` ก่อนรัน หรือรันด้วย `java -Dstdout.encoding=UTF-8 Main ...` ส่วนค่าใน SUMMARY เป็นภาษาอังกฤษทั้งหมดจึงอ่านได้ทุกเครื่อง
