# High Concurrency Flash Sale System - Architecture

## 1. System Architecture Overview

The system handles 100,000+ concurrent requests by strictly adhering to a **Queue-Based Asynchronous** architecture utilizing Redis for edge-level atomic checks and RabbitMQ for persistent buffer.

### 1.1 Architecture Diagram

```mermaid
graph TD
    Client[Client / Bots] -->|POST /buy| RL[Rate Limiter & Anti-Bot]
    RL -->|Validated| API[Spring Boot API]
    
    API <-->|Atomic Reserve| Redis[(Redis Lua Script)]
    API -->|Publish Event| RMQ[RabbitMQ Exchange]
    
    RMQ -->|Consume Queue| Worker[Spring Boot Worker]
    Worker -->|Transactional Save| DB[(PostgreSQL)]
```

### 1.2 Sequence Diagram

```mermaid
sequenceDiagram
    participant Client
    participant Rate Limiter
    participant Spring Boot
    participant Redis Lua
    participant RabbitMQ
    participant Worker
    participant PostgreSQL

    Client->>Rate Limiter: POST /api/v1/flash-sale/buy
    Rate Limiter->>Spring Boot: Pass (If allowed)
    
    Spring Boot->>Spring Boot: Validate HMAC & Timestamp & Replay
    
    Spring Boot->>Redis Lua: EVAL reserve_inventory.lua
    alt SOLD OUT / DUPLICATE
        Redis Lua-->>Spring Boot: Return 0 / -1
        Spring Boot-->>Client: 409 Conflict / 202 (Idempotent)
    else RESERVED
        Redis Lua-->>Spring Boot: Return 1
        Spring Boot->>RabbitMQ: Publish InventoryReservedEvent
        Spring Boot-->>Client: 202 Accepted (requestId)
    end
    
    Note over RabbitMQ, PostgreSQL: Asynchronous Order Processing
    
    RabbitMQ->>Worker: Consume Event
    Worker->>PostgreSQL: BEGIN Transaction
    Worker->>PostgreSQL: Check IdempotencyRecord
    Worker->>PostgreSQL: Insert Order & OrderItem
    Worker->>PostgreSQL: Update Product Inventory
    Worker->>PostgreSQL: Update IdempotencyRecord -> COMPLETED
    Worker->>PostgreSQL: COMMIT Transaction
    
    Worker-->>RabbitMQ: ACK Message
    
    Note over Client, PostgreSQL: Client can poll for status using requestId
```

### 1.3 State Diagram

```mermaid
stateDiagram-v2
    [*] --> PROCESSING: Request Received
    PROCESSING --> RESERVED: Redis Lua Success
    PROCESSING --> SOLD_OUT: Redis Inventory = 0
    PROCESSING --> FAILED: Rate Limit / Invalid Auth
    RESERVED --> CONFIRMED: Worker Commits DB
    RESERVED --> DEAD_LETTER_QUEUE: DB Fails 3x (DLQ)
    CONFIRMED --> [*]
    SOLD_OUT --> [*]
    FAILED --> [*]
```

### 1.4 Failure Recovery Diagram

```mermaid
graph TD
    A[Worker Consumes Message] --> B{DB Transaction}
    B -->|Success| C[Commit DB & ACK RMQ]
    
    B -->|DB Timeout / Deadlock| D[Rollback & Throw Exception]
    D --> E{Retry Count < 3?}
    
    E -->|Yes| F[Requeue in RabbitMQ with Delay]
    F --> A
    
    E -->|No| G[NACK Message]
    G --> H[Route to DLQ: flash-sale.order.dlq]
    H --> I[Manual Intervention / Cron Job]
```
