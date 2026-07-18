# JMeter 压测操作指南

这份文档是给第一次做压测的人准备的，目标不是一下子打很高的并发，而是先把当前竞拍系统的关键链路跑通，并观察会不会出现价格、排行榜、余额、保证金不一致的问题。

当前项目的压测顺序按下面 4 步来：

1. 先压 `GET /api/auctions/{roomId}`，确认房间能升为热房间
2. 再压 `POST /api/auctions/{roomId}/registrations`，确认保证金冻结没问题
3. 再压 `POST /api/auctions/{roomId}/bids`，观察热房间出价链路
4. 最后做结束前冲刺压测

---

## 1. 压测前准备

先确认下面几个服务都已经启动：

- 前端不是必须启动，但后端必须启动
- MySQL 已启动
- Redis 已启动
- RabbitMQ 已启动

后端地址默认是：

```text
http://localhost:8080
```

可以先在浏览器里访问：

```text
http://localhost:8080/api/auctions
```

如果能返回房间列表，说明后端接口已经起来了。

---

## 2. 这次会用到的接口

### 2.1 查询房间详情

```http
GET /api/auctions/{roomId}
```

例子：

```http
GET http://localhost:8080/api/auctions/AR-1001
```

### 2.2 报名竞拍

```http
POST /api/auctions/{roomId}/registrations
Content-Type: application/json
```

请求体：

```json
{
  "userId": "u10001",
  "nickname": "用户1"
}
```

### 2.3 出价

```http
POST /api/auctions/{roomId}/bids
Content-Type: application/json
```

请求体：

```json
{
  "userId": "u10001",
  "nickname": "用户1",
  "amount": 120.00
}
```

### 2.4 充值

```http
POST /api/users/{userId}/recharge
Content-Type: application/json
```

请求体：

```json
{
  "amount": 5000.00
}
```

---

## 3. 建议你先准备一个测试房间

第一次压测不要直接拿很长时间的房间来跑，建议先用一个测试房间。

推荐参数：

- 房间 ID：创建后记下来，例如 `AR-1008`
- 起拍价：`100`
- 加价幅度：`1`
- 保证金：`50`
- 持续时间：`120` 秒或 `180` 秒

这样比较方便做最后的结束前冲刺测试。

---

## 4. 建议你先准备一批测试账号

如果你已经有账号，可以直接用。  
如果没有，建议数据库里准备一批，例如：

- `u10001`
- `u10002`
- `u10003`
- `u10004`
- `u10005`

这些账号都先充一点钱，保证压测时不会因为余额不足全失败。

---

## 5. JMeter 基础搭建

## 5.1 新建测试计划

打开 JMeter 后：

1. 右键 `Test Plan`
2. 选择 `Add -> Threads (Users) -> Thread Group`

先建 4 个线程组，对应 4 个阶段：

- `01-预热房间`
- `02-报名竞拍`
- `03-多人出价`
- `04-结束前冲刺`

---

## 5.2 添加公共配置

建议在 `Test Plan` 下统一加这几个组件。

### HTTP Request Defaults

右键 `Test Plan`

```text
Add -> Config Element -> HTTP Request Defaults
```

填写：

- Protocol: `http`
- Server Name or IP: `localhost`
- Port Number: `8080`

### HTTP Header Manager

右键 `Test Plan`

```text
Add -> Config Element -> HTTP Header Manager
```

添加一行：

- Name: `Content-Type`
- Value: `application/json`

### View Results Tree

右键 `Test Plan`

```text
Add -> Listener -> View Results Tree
```

第一次调试非常有用，能看到每个请求的返回内容。

### Summary Report

右键 `Test Plan`

```text
Add -> Listener -> Summary Report
```

这个主要看：

- Samples
- Average
- Min
- Max
- Error %
- Throughput

### Aggregate Report

右键 `Test Plan`

```text
Add -> Listener -> Aggregate Report
```

这个主要看：

- Average
- 90% Line
- 95% Line
- 99% Line

---

## 6. 准备一个 CSV 用户文件

为了方便多人并发，建议用 `CSV Data Set Config`。

你可以自己创建一个文件，比如：

```text
D:\auction\jmeter-users.csv
```

文件内容示例：

```csv
userId,nickname
u10001,用户1
u10002,用户2
u10003,用户3
u10004,用户4
u10005,用户5
u10006,用户6
u10007,用户7
u10008,用户8
u10009,用户9
u10010,用户10
```

然后在 `Test Plan` 下添加：

```text
Add -> Config Element -> CSV Data Set Config
```

填写：

- Filename: `D:/auction/jmeter-users.csv`
- File encoding: `UTF-8`
- Variable Names: `userId,nickname`
- Ignore first line: `True`
- Recycle on EOF: `True`
- Stop thread on EOF: `False`
- Sharing mode: `All threads`

---

## 7. 第一阶段：压 GET 房间详情，让房间升 HOT

这个阶段的目的是模拟围观，让房间升级为热房间。

### 7.1 线程组参数

线程组 `01-预热房间` 推荐先这样配：

- Number of Threads: `50`
- Ramp-Up Period: `5`
- Loop Count: `20`

### 7.2 在线程组中添加请求

右键 `01-预热房间`

```text
Add -> Sampler -> HTTP Request
```

命名：

```text
GET-房间详情
```

填写：

- Method: `GET`
- Path: `/api/auctions/AR-1001`

把 `AR-1001` 换成你自己的测试房间 ID。

### 7.3 加一个定时器

右键 `01-预热房间`

```text
Add -> Timer -> Uniform Random Timer
```

填写：

- Random Delay Maximum: `300`
- Constant Delay Offset: `100`

这样不会所有线程毫秒级同时发请求，更接近真实访问。

### 7.4 运行后看什么

跑完后观察：

- 房间接口返回是否成功
- 前端或数据库里这个房间是否变成热房间
- Redis 里是否出现类似下面的 key

```text
auction:room:{roomId}:mode
auction:room:{roomId}:hot-state
auction:room:{roomId}:leaderboard
auction:room:{roomId}:recent-bids
```

如果房间没变 HOT，说明访问量还没打到你配置的阈值，可以把线程数升到 `100` 再试一次。

---

## 8. 第二阶段：报名竞拍，验证保证金冻结

这个阶段目标是确认报名后用户余额会减少、冻结金额会上升。

### 8.1 先给用户充值

建议在 `02-报名竞拍` 线程组里先加一个充值请求。

线程组参数建议：

- Number of Threads: `10`
- Ramp-Up Period: `2`
- Loop Count: `1`

### 8.2 添加充值请求

添加 `HTTP Request`，命名：

```text
POST-用户充值
```

填写：

- Method: `POST`
- Path: `/api/users/${userId}/recharge`

Body Data：

```json
{
  "amount": 5000.00
}
```

### 8.3 添加报名请求

再加一个 `HTTP Request`，命名：

```text
POST-报名竞拍
```

填写：

- Method: `POST`
- Path: `/api/auctions/AR-1001/registrations`

Body Data：

```json
{
  "userId": "${userId}",
  "nickname": "${nickname}"
}
```

### 8.4 建议加断言

右键 `POST-报名竞拍`

```text
Add -> Assertions -> Response Assertion
```

选择：

- Field to Test: `Response Text`
- Pattern Matching Rules: `Contains`

添加一个断言文本：

```text
success
```

### 8.5 跑完后怎么验证

你可以去看：

- `user_account.balance`
- `user_account.frozen_amount`
- `auction_room_registration`

预期结果：

- `balance` 减少
- `frozen_amount` 增加
- 报名表里状态变成 `LOCKED`

---

## 9. 第三阶段：多人同时出价，观察热房间链路

这是最关键的压测阶段。

### 9.1 线程组参数

线程组 `03-多人出价` 先从小规模开始：

第一轮：

- Number of Threads: `20`
- Ramp-Up Period: `2`
- Loop Count: `3`

第二轮：

- Number of Threads: `50`
- Ramp-Up Period: `3`
- Loop Count: `5`

第三轮：

- Number of Threads: `100`
- Ramp-Up Period: `5`
- Loop Count: `5`

不要一开始直接上很大，否则你很难知道是哪一层先出问题。

### 9.2 添加出价请求

在线程组下添加 `HTTP Request`，命名：

```text
POST-多人出价
```

填写：

- Method: `POST`
- Path: `/api/auctions/AR-1001/bids`

Body Data 示例：

```json
{
  "userId": "${userId}",
  "nickname": "${nickname}",
  "amount": 200.00
}
```

### 9.3 第一次先固定金额

第一次压测建议先固定一个金额，不要一开始就做复杂递增。

这样做的目的不是让全部成功，而是先观察：

- 系统能否正确拒绝低价
- 热房间 Redis 出价链路是否稳定
- 错误返回是否正常

### 9.4 第二次再做递增金额

如果你想更真实一点，可以先手工分几组请求，比如：

- 一组出 `200`
- 一组出 `201`
- 一组出 `202`

第一次不用在 JMeter 里做复杂脚本，先跑通比什么都重要。

### 9.5 重点看什么

跑完后重点观察：

- 是否有成功请求但金额没更新
- 房间当前价是否等于最高出价
- 排行榜第一是否等于最高出价用户
- `version` 是否持续递增
- RabbitMQ 是否有堆积
- MySQL 的 `auction_room.current_price` 是否明显落后 Redis

---

## 10. 第四阶段：结束前冲刺压测

这个阶段是最容易暴露竞拍系统问题的。

### 10.1 准备一个短时房间

建议新建一个只有 `60` 到 `90` 秒的房间。

### 10.2 操作方式

先完成前面 3 个阶段，把房间打热、用户报上名、余额充足。

然后在房间快结束前的最后 `10` 到 `15` 秒，启动一个冲刺线程组。

### 10.3 线程组参数

线程组 `04-结束前冲刺` 建议：

- Number of Threads: `30`
- Ramp-Up Period: `1`
- Loop Count: `2`

请求还是：

```text
POST /api/auctions/{roomId}/bids
```

### 10.4 重点观察

看下面这些是否正确：

- 已结束房间是否还能继续成功出价
- 成交价是否等于最后的有效最高价
- 成交人是否等于最后赢家
- 保证金是否释放
- 领先冻结金额是否被正确消费或释放

---

## 11. 第一次压测最推荐的执行顺序

可以按下面顺序跑。

### 第 1 轮：只测房间升 HOT

- 跑 `01-预热房间`
- 确认 Redis 里已经有 HOT 标记

### 第 2 轮：测报名与保证金

- 跑 `02-报名竞拍`
- 看数据库里余额和冻结金额是否变化正确

### 第 3 轮：测多人并发出价

- 跑 `03-多人出价`
- 先从 20 线程开始
- 没问题再上 50、100

### 第 4 轮：测结束前冲刺

- 新建短时房间
- 在最后十几秒跑 `04-结束前冲刺`

---

## 12. 你要重点关注的风险

当前这套系统在并发下最可能出现这些问题：

- Redis 出价成功了，但后面的余额冻结失败
- 同一用户多个请求同时出价时，余额判断不够原子
- RabbitMQ 消费跟不上，导致 MySQL 比 Redis 落后
- 房间结束瞬间，关闭和出价发生竞争
- 排行榜、当前价、成交页在短时间内不一致

所以这次压测最重要的不是看吞吐量，而是看：

- 数据对不对
- 顺序乱不乱
- 钱扣得对不对

---

## 13. 建议你每轮压测后检查的地方

### 数据库

看这几张表：

- `auction_room`
- `auction_bid_record`
- `auction_room_registration`
- `user_account`

重点看：

- `auction_room.current_price`
- `auction_room.leader_user_id`
- `auction_room.version`
- `auction_bid_record.bid_version`
- `user_account.balance`
- `user_account.frozen_amount`

### Redis

看这些 key：

- `auction:room:{roomId}:mode`
- `auction:room:{roomId}:hot-state`
- `auction:room:{roomId}:leaderboard`
- `auction:room:{roomId}:leaderboard:profile`
- `auction:room:{roomId}:recent-bids`

### RabbitMQ

看：

- 队列有没有堆积
- 消费速度能不能跟上

---

## 14. 第一次压测时的一个小建议

第一次压测请一定打开：

- `View Results Tree`
- 后端控制台日志
- Redis 可视化工具或命令行
- RabbitMQ 管理界面

这样你会非常容易看明白：

- 请求有没有打进去
- 房间有没有变热
- Redis 有没有实时更新
- RabbitMQ 有没有在刷库

---

## 15. 如果你要继续进阶

第一次跑通以后，下一步可以再做这些增强：

1. 用 CSV 拆成多批用户，模拟不同等级的竞拍人群
2. 给出价金额做自动递增
3. 加 `JSON Extractor` 提取 `version` 和 `currentPrice`
4. 用 `Response Assertion` 校验关键字段
5. 把压测结果导出成报告，整理成项目文档

---

## 16. 当前最简单可执行的起步配置

如果你只想先跑第一轮，直接照这个做就行。

### 线程组 1：预热房间

- 50 线程
- 5 秒 Ramp-Up
- 20 次循环
- 请求：`GET /api/auctions/AR-1001`

### 线程组 2：报名竞拍

- 10 线程
- 2 秒 Ramp-Up
- 1 次循环
- 先 `POST /api/users/${userId}/recharge`
- 再 `POST /api/auctions/AR-1001/registrations`

### 线程组 3：多人出价

- 20 线程
- 2 秒 Ramp-Up
- 3 次循环
- 请求：`POST /api/auctions/AR-1001/bids`

### 线程组 4：结束前冲刺

- 30 线程
- 1 秒 Ramp-Up
- 2 次循环
- 请求：`POST /api/auctions/AR-1001/bids`

---

## 17. 你下一步怎么做

你可以先按这份文档把 JMeter 场景搭起来。  
建议先不要追求“自动递增金额”这种复杂脚本，先把链路跑顺、结果看懂最重要。

如果你愿意，下一步我可以继续帮你补两样东西：

1. 一份 `jmeter-users.csv` 示例文件
2. 一份更细的“JMeter 每一步点哪里”的图文版结构说明
