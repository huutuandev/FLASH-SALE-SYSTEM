# Implementation Checklist

## Phase 1: Setup & Infrastructure
- [ ] Khởi tạo Spring Boot 3.x project với Java 17, Maven.
- [ ] Thêm các dependencies: Spring Web, Spring Data JPA, Spring Security, Spring Data Redis, Spring AMQP (RabbitMQ).
- [ ] Viết file `docker-compose.yml` để chạy PostgreSQL, Redis, và RabbitMQ.
- [ ] Cấu hình `application.yml` cho database connection pool (HikariCP), Redis, RabbitMQ.
- [ ] Thiết kế Database Schema: 
  - Table `users` (id, username, ...).
  - Table `products` (id, name, ...).
  - Table `inventory` (id, product_id, stock).
  - Table `orders` (id, user_id, product_id, status).

## Phase 2: Core Redis & Messaging (The Flash Sale Logic)
- [ ] Viết Lua Script (`reserve_stock.lua`) thực hiện logic:
  - Kiểm tra số lượng stock hiện tại (> 0).
  - Kiểm tra UserID đã mua chưa (dùng Set `SISMEMBER`).
  - Trừ stock (`DECR`).
  - Thêm UserID vào Set đã mua (`SADD`).
  - Trả về kết quả thành công/thất bại.
- [ ] Cấu hình `RedisTemplate` và `RedisScript` bean trong Spring Boot.
- [ ] Cấu hình RabbitMQ: tạo `DirectExchange`, `Queue`, `Binding`, và `DeadLetterExchange` (DLQ) cho failover.
- [ ] Viết `FlashSaleService` gọi Lua script và `RabbitTemplate.convertAndSend` khi reserve thành công.

## Phase 3: PostgreSQL Worker & Data Consistency
- [ ] Viết `OrderWorker` (hoặc `OrderListener`) với annotation `@RabbitListener` để consume message từ queue.
- [ ] Thực hiện `@Transactional` DB logic trong worker:
  - Insert bản ghi vào bảng `orders` (trạng thái `CONFIRMED`).
  - Update giảm stock trong bảng `inventory`.
- [ ] Cài đặt logic Compensating Transaction trong trường hợp DB lỗi không thể retry:
  - Bắt Exception.
  - Cập nhật lại Redis (Tăng stock, xóa User khỏi Set).
  - Đánh dấu order `FAILED`.
- [ ] Đảm bảo tính Idempotency bằng database constraints (`UNIQUE` cho `user_id` + `product_id`).

## Phase 4: API & Anti-Bot
- [ ] Viết `FlashSaleController` expose API `POST /api/v1/flash-sale/buy`.
- [ ] Triển khai IP Rate Limiting bằng Redis hoặc Bucket4j.
- [ ] Cài đặt token validation (VD: CAPTCHA token hoặc Auth token cho user hợp lệ).
- [ ] Implement API để client poll trạng thái order (bởi vì API Buy trả về `202 Accepted`).

## Phase 5: Testing & Benchmarking
- [ ] Viết Unit Tests (JUnit 5, Mockito) cho service và Lua script behavior.
- [ ] Viết Integration Tests sử dụng Testcontainers (khởi chạy Postgres, Redis, RabbitMQ thật trong Docker) để test flow end-to-end.
- [ ] Dùng JMeter / Gatling / K6 viết kịch bản bắn 100,000 requests cùng lúc vào API `/buy`.
- [ ] Monitor CPU, RAM, Connection pool của DB/Redis trong lúc load test để fine-tune thông số.
