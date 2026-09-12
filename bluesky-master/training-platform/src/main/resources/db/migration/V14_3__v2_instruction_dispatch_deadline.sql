-- P0-6/E12（评审）：指令派发 5 秒技术确认窗口落库。
-- deadline 只存在于内存时重启即丢失（评审 E12）；落库后看门狗可跨重启扫描超时。
ALTER TABLE aircraft_instruction ADD COLUMN dispatch_deadline_at TIMESTAMP(3);
