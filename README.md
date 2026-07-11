# 直播竞拍全栈系统骨架

这是一个适合继续扩展的初版项目框架，目标是先把直播竞拍闭环跑通：

- 商家/主播创建拍卖房
- 用户进入房间查看当前价和倒计时
- 用户实时出价
- 服务端广播最新价格、领先者和剩余时间
- 最后时刻自动延时
- 截止后自动落锤

## 目录结构

```text
auction
├─ backend   Spring Boot 后端
└─ frontend  Vite + Vanilla JS 前端
```

## 后端模块

- `auction/controller`：REST 接口
- `auction/service`：竞拍业务逻辑和广播
- `auction/model`：竞拍房、出价记录、状态
- `config`：CORS 和 WebSocket 配置
- `common`：统一返回体、异常处理

当前后端是内存版实现，适合先验证业务流。下一步可以逐步替换成：

- Redis：缓存房间状态、排行榜、并发锁
- MySQL：拍卖场、出价流水、订单、支付记录
- MQ：落锤、支付、退款、通知等异步流程

## 启动方式

### 1. 启动后端

```powershell
cd D:\auction\backend
D:\Maven\apache-maven-3.9.11\bin\mvn.cmd spring-boot:run
```

### 2. 启动前端

```powershell
cd D:\auction\frontend
npm install
npm run dev
```

前端默认地址：

- `http://localhost:5173`

后端默认地址：

- `http://localhost:8080`

## 已提供接口

- `GET /api/auctions`：查询拍卖房列表
- `GET /api/auctions/{roomId}`：查询拍卖房详情
- `POST /api/auctions`：创建拍卖房
- `POST /api/auctions/{roomId}/bids`：提交出价
- `GET ws://localhost:8080/ws-auction`：WebSocket 连接入口

## 下一步建议

1. 接入 MySQL，把房间、拍品、出价记录持久化
2. 引入 Redis，给单房间竞价做热点缓存和原子控制
3. 增加订单、支付、保证金、违约处理
4. 拆分主播后台、用户竞拍页、运营监控页
5. 把内存广播替换成 Redis Pub/Sub 或 MQ 广播
