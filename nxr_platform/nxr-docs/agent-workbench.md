# 代理工作台

代理在 `/account/agent` 管理自己的客户、来件和最终回寄。账户须由 NXR 管理员设为 `merchant`；所有接口使用已有客户令牌，服务端逐条校验代理归属。普通客户和其他代理不能读取档案、卡片、事件或照片。

## 业务流程

1. 建立客户档案。联系方式和地址可后补；归档禁止新来件，保留历史和已有库存的回寄能力。
2. 登记客户寄来的包裹和预期卡片清单。每张卡生成独立库存码，可打印二维码标签。每件来件对应一个 NXR 子订单，因此数量同时受当前送评上限约束。
3. 签收包裹，逐张扫描清点。少件或存在异常不能送评；纠正异常有事件记录，旧请求重放不会覆盖新清点结果。照片使用原有私有存储，并保留为代理证据。
4. 将完整来件合成送评批次，选择代理自己的返程地址、币种和服务。后台复用原来的报价和批次服务，在一个事务里创建订单、绑定库存及照片。页面报价发生变化时整批回滚并要求重新报价。相同请求键不会创建重复批次。
5. 按原有流程进行 NXR 受理、条款确认、预充值付款、主包裹寄入、评审、封装和整包回代理。客户联系方式留在代理档案，NXR 联系人仍为代理。
6. 主包裹在 NXR 流程中确认已送达代理后，代理逐张扫描库存码、对应证号或本域证书二维码核对回卡。只有与该库存卡绑定的证号有效，不通过卡名猜测归属。
7. 按同一客户选择已核对卡片回寄，保存收件地址快照和运单号，再登记客户签收。重复选卡、混入另一客户或未核对卡片均被拦截。该段状态不覆盖 NXR 原订单和主包裹状态。

物流信息目前由操作人员登记和确认，未新增承运商自动查件接口。预充值仍为代理申请、平台人工核款入账；没有新增授信、代理零售收款或员工子账户体系。

## 实现入口

- `customer/AgentWorkbenchService`：代理归属、清点状态、事务及回寄规则。
- `customer/AgentWorkbenchController`：`/api/customer/agent` 下的客户、来件、库存、提交、回寄和事件接口，响应禁止缓存。
- `customer/MerchantBatchService.createBatchInCurrentTransaction`：内部原子接入；旧批次入口事务语义保持不变。
- `CustomerOrderPhotoService`：保留代理证据、过滤未使用配额，并在库存与订单映射确认后绑定照片。
- `WebAgentWorkbenchView` 与 `components/agent-workbench/`：客户档案、来件与库存、客户回寄三个面板。

新增表为 `agent_client`、`agent_intake`、`agent_card`、`agent_submission`、`agent_return_shipment`、`agent_event`、`agent_operation`。订单和钱包沿用原表；来件、库存码、订单项、最终回寄保持可追溯关联。

## 迁移和验证

`23_nxr_agent_workbench.sql` 在迁移 22 之后执行，增加代理表及照片保留标记，支持重复执行。已有数据库须先核实环境并备份后增量迁移；生产数据变更需单独授权。不要为升级运行会重建数据的初始化脚本，也不要操作 Python 的 `Data/`。

可复现的本地 API 验收使用专门的新数据库，准备脚本拒绝覆盖已有库：

```bash
python3 nxr-scripts/prepare-agent-workbench-qa.py --database nxr_acceptance_agent_example --apply
# 启动指向该库的隔离 QA 后端，再运行：
NXR_QA_DATABASE=nxr_acceptance_agent_example python3 nxr-scripts/verify-agent-workbench.py
```

QA 使用回环地址 8090，并先通过唯一服务价格标记核对数据库，之后才生成测试账户和数据。真实支付及邮件发送保持关闭。需要并行验证时可通过 `NXR_QA_REDIS_DATABASE` 和 Spring Redis 端口环境变量指定专用缓存实例。

验证范围包括双代理/C端隔离、库存防混、清点重放、报价变化回滚、并发提交/回寄、防提前放行、照片归属、地址快照，以及原有 NXR 受理、钱包扣费、逐卡评级和主包裹物流到最终客户签收的完整衔接。
