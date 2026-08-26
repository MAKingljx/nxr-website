<template>
  <main class="nxr-workspace nxr-orders-workspace">
    <nxr-page-header
      kicker="GRADING ORDERS"
      title="订单管理"
      summary="处理收款、扫码入库、评级作业、质检、回寄、差价和客服工单"
    >
      <template #actions>
        <el-button v-hasPermi="['nxr:order:manage','nxr:order:config']" icon="Setting" @click="openShippingConfig">回寄方案配置</el-button>
      </template>
    </nxr-page-header>

    <el-card v-hasPermi="['nxr:order:manage','nxr:order:warehouse']" shadow="never" class="intake-lookup-card mb12">
      <div class="intake-lookup-row"><strong>仓库扫码收件</strong><el-input v-model="intakeLookupCode" clearable placeholder="扫描或输入订单入库码" @keyup.enter="lookupIntakeOrder" /><el-button type="primary" icon="Search" :loading="lookingUpIntake" @click="lookupIntakeOrder">查找订单</el-button></div>
    </el-card>

    <el-form ref="queryRef" :model="queryParams" :inline="true" @submit.prevent>
      <el-form-item label="订单状态" prop="status">
        <el-select v-model="queryParams.status" clearable placeholder="全部状态" style="width: 180px">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="关键词" prop="query">
        <el-input v-model="queryParams.query" clearable placeholder="订单号 / 客户邮箱 / 客户名称" style="width: 260px" @keyup.enter="loadOrders(true)" />
      </el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="loadOrders(true)">搜索</el-button><el-button icon="Refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="订单号" prop="orderNo" min-width="150" />
      <el-table-column label="客户" min-width="190" show-overflow-tooltip><template #default="scope"><div>{{ scope.row.customer.displayName }}</div><small>{{ scope.row.customer.email }}</small></template></el-table-column>
      <el-table-column label="卡数" prop="totalCardCount" width="80" align="center" />
      <el-table-column label="服务" prop="serviceLevelCode" width="105" align="center" />
      <el-table-column label="金额" width="110" align="right"><template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.totalAmount).toFixed(2) }}</template></el-table-column>
      <el-table-column label="状态" width="150" align="center"><template #default="scope"><el-tag :type="statusType(scope.row.statusCode)">{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column>
      <el-table-column label="创建时间" width="170" align="center"><template #default="scope">{{ parseTime(scope.row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="100" align="center"><template #default="scope"><el-button link type="primary" icon="View" @click="openDetail(scope.row.id)">详情</el-button></template></el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.page" v-model:limit="queryParams.pageSize" @pagination="loadOrders" />

    <el-dialog v-model="detailOpen" title="送评订单详情" width="1080px" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="3" border class="mb12">
          <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="订单状态"><el-tag :type="statusType(detail.statusCode)">{{ labelStatus(detail.statusCode) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="服务 / 卡数">{{ detail.serviceLevelCode }} / {{ detail.totalCardCount }}</el-descriptions-item>
          <el-descriptions-item label="客户" :span="2">{{ detail.customer.displayName }} · {{ detail.customer.email }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ detail.contactPhone }}</el-descriptions-item>
          <el-descriptions-item label="回寄地址" :span="3">{{ [detail.returnAddressLine1, detail.returnAddressLine2, detail.returnCity, detail.returnRegion, detail.returnPostalCode, detail.returnCountry].filter(Boolean).join(', ') }}</el-descriptions-item>
          <el-descriptions-item label="金额">{{ detail.currencyCode }} {{ Number(detail.totalAmount).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="服务费">{{ detail.currencyCode }} {{ Number(detail.serviceFee).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="回寄运费">{{ detail.currencyCode }} {{ Number(detail.returnShippingFee).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item label="客户备注" :span="3">{{ detail.customerNote || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">仓库收件与异常</el-divider>
        <el-form v-if="operations" v-hasPermi="['nxr:order:manage','nxr:order:warehouse']" :inline="true" :model="intakeForm" class="operation-form">
          <el-form-item label="入库码"><el-input v-model="intakeForm.intakeCode" style="width:180px" /></el-form-item>
          <el-form-item label="包裹号"><el-input v-model="intakeForm.packageNo" style="width:150px" /></el-form-item>
          <el-form-item label="实收卡数"><el-input-number v-model="intakeForm.receivedCount" :min="0" :max="1000" /></el-form-item>
          <el-form-item label="异常"><el-select v-model="intakeForm.exceptionTypes" multiple clearable style="width:220px"><el-option label="破损" value="damaged" /><el-option label="缺少条码" value="missing_barcode" /><el-option label="其他" value="other" /></el-select></el-form-item>
          <el-form-item label="备注"><el-input v-model="intakeForm.conditionNote" style="width:220px" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="receivingOrder" @click="receiveOrder">确认扫码入库</el-button></el-form-item>
        </el-form>
        <el-empty v-if="operations && !operations.receipts.length && !operations.exceptions.length" description="暂无入库记录" :image-size="58" />
        <el-table v-if="operations?.receipts.length" :data="operations.receipts" size="small" border class="mb12">
          <el-table-column label="接收时间" width="170"><template #default="scope">{{ parseTime(scope.row.receivedAt) }}</template></el-table-column><el-table-column label="包裹号" prop="packageNo" min-width="150" /><el-table-column label="应收" prop="expectedCount" width="80" align="center" /><el-table-column label="实收" prop="receivedCount" width="80" align="center" /><el-table-column label="收件人 ID" prop="receivedByUserId" width="100" align="center" /><el-table-column label="备注" prop="conditionNote" min-width="180" />
        </el-table>
        <el-table v-if="operations?.exceptions.length" :data="operations.exceptions" size="small" border>
          <el-table-column label="异常类型" width="130"><template #default="scope">{{ labelStatus(scope.row.exceptionTypeCode) }}</template></el-table-column><el-table-column label="标题" prop="title" min-width="170" /><el-table-column label="详情" prop="detail" min-width="220" /><el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="scope.row.statusCode === 'resolved' ? 'success' : 'danger'">{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column><el-table-column label="处理结果" prop="resolutionNote" min-width="180" /><el-table-column label="操作" width="100"><template #default="scope"><el-button v-if="scope.row.statusCode !== 'resolved'" v-hasPermi="['nxr:order:manage','nxr:order:warehouse','nxr:order:support']" link type="primary" @click="openExceptionResolution(scope.row)">处理</el-button></template></el-table-column>
        </el-table>

        <el-divider content-position="left">订单卡牌与评分关联</el-divider>
        <el-table :data="detail.items" size="small" border>
          <el-table-column label="#" prop="itemNo" width="55" align="center" />
          <el-table-column label="卡名" prop="cardName" min-width="180" />
          <el-table-column label="信息" min-width="180"><template #default="scope">{{ [scope.row.brandName, scope.row.setName, scope.row.cardNumber, scope.row.languageCode].filter(Boolean).join(' · ') || '-' }}</template></el-table-column>
          <el-table-column label="条目状态" width="130"><template #default="scope"><el-tag>{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column>
          <el-table-column label="评分条目" min-width="220"><template #default="scope"><span v-if="scope.row.gradingSubmissionId">#{{ scope.row.gradingSubmissionId }} · {{ scope.row.gradingCertId }} · {{ scope.row.gradingStatusCode }}</span><div v-else class="link-submission"><el-input-number v-model="submissionLinks[scope.row.id]" :min="1" :controls="false" placeholder="评分条目 ID" /><el-button v-hasPermi="['nxr:order:manage','nxr:order:grading']" link type="primary" :loading="linkingItemId === scope.row.id" @click="linkSubmission(scope.row)">关联</el-button></div></template></el-table-column>
        </el-table>

        <el-divider content-position="left">内部作业与整体质检</el-divider>
        <template v-if="operations">
          <el-form v-hasPermi="['nxr:order:manage','nxr:order:grading']" :inline="true" :model="taskForm" class="operation-form">
            <el-form-item label="任务类型"><el-select v-model="taskForm.taskTypeCode" style="width:170px"><el-option v-for="item in taskTypeOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            <el-form-item label="订单卡片"><el-select v-model="taskForm.orderItemId" clearable placeholder="整单任务" style="width:190px"><el-option v-for="item in detail.items" :key="item.id" :label="`${item.itemNo}. ${item.cardName}`" :value="item.id" /></el-select></el-form-item>
            <el-form-item><el-button type="primary" :loading="creatingTask" @click="createTask">新增任务</el-button></el-form-item>
          </el-form>
          <el-table :data="operations.workTasks" size="small" border>
            <el-table-column label="任务" width="150"><template #default="scope">{{ taskLabel(scope.row.taskTypeCode) }}</template></el-table-column><el-table-column label="卡片 ID" prop="orderItemId" width="90" align="center" /><el-table-column label="状态" width="150"><template #default="scope"><el-select v-model="taskDrafts[scope.row.id].statusCode" size="small"><el-option label="待处理" value="pending" /><el-option label="进行中" value="in_progress" /><el-option label="已完成" value="completed" /><el-option label="失败/重试" value="failed" /></el-select></template></el-table-column><el-table-column label="尝试次数" prop="attemptCount" width="90" align="center" /><el-table-column label="结果 / 失败原因" min-width="240"><template #default="scope"><el-input v-model="taskDrafts[scope.row.id].summary" size="small" :placeholder="taskDrafts[scope.row.id].statusCode === 'failed' ? '填写失败原因' : '填写作业结果'" /></template></el-table-column><el-table-column label="操作" width="100"><template #default="scope"><el-button v-hasPermi="['nxr:order:manage','nxr:order:grading']" link type="primary" :loading="savingTaskId === scope.row.id" @click="saveTask(scope.row)">保存</el-button></template></el-table-column>
          </el-table>
          <el-form v-hasPermi="['nxr:order:manage','nxr:order:grading']" :inline="true" :model="qualityForm" class="operation-form quality-form"><el-form-item label="终检结论"><el-radio-group v-model="qualityForm.passed"><el-radio-button :value="true">合格</el-radio-button><el-radio-button :value="false">返工</el-radio-button></el-radio-group></el-form-item><el-form-item label="终检说明"><el-input v-model="qualityForm.note" style="width:300px" /></el-form-item><el-form-item><el-button type="success" :loading="savingQuality" @click="saveQualityCheck">提交终检</el-button></el-form-item></el-form>
        </template>

        <el-divider content-position="left">收款与账务流水</el-divider>
        <el-table :data="detail.payments" size="small" border>
          <el-table-column label="类型" width="120"><template #default="scope">{{ scope.row.paymentTypeCode }}</template></el-table-column>
          <el-table-column label="金额" width="130"><template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.amount).toFixed(2) }}</template></el-table-column>
          <el-table-column label="方式" min-width="140"><template #default="scope">{{ scope.row.providerCode }}</template></el-table-column>
          <el-table-column label="付款参考" min-width="180"><template #default="scope">{{ scope.row.payerReference || '-' }}</template></el-table-column>
          <el-table-column label="状态" width="140"><template #default="scope"><el-tag :type="paymentType(scope.row.statusCode)">{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="150"><template #default="scope"><el-button v-if="canReviewPayment(scope.row)" v-hasPermi="['nxr:order:manage','nxr:order:payment']" link type="success" @click="openPaymentAction(scope.row, 'confirm')">确认</el-button><el-button v-if="canReviewPayment(scope.row)" v-hasPermi="['nxr:order:manage','nxr:order:payment']" link type="danger" @click="openPaymentAction(scope.row, 'reject')">驳回</el-button></template></el-table-column>
        </el-table>

        <el-divider content-position="left">物流与进度</el-divider>
        <el-row :gutter="16" class="mb12">
          <el-col v-hasPermi="['nxr:order:manage']" :span="12"><el-form :inline="true" :model="statusForm"><el-form-item label="推进状态"><el-select v-model="statusForm.statusCode" style="width: 180px"><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item><el-form-item><el-button type="primary" :loading="savingStatus" @click="saveStatus">保存</el-button></el-form-item></el-form></el-col>
          <el-col v-hasPermi="['nxr:order:manage','nxr:order:shipping']" :span="12"><el-form :inline="true" :model="shipmentForm"><el-form-item label="物流方向"><el-select v-model="shipmentForm.direction" style="width: 100px"><el-option label="收件" value="inbound" /><el-option label="回寄" value="outbound" /></el-select></el-form-item><el-form-item label="承运商"><el-input v-model="shipmentForm.carrierName" style="width: 120px" /></el-form-item><el-form-item label="单号"><el-input v-model="shipmentForm.trackingNumber" style="width: 150px" /></el-form-item><el-form-item><el-button type="primary" :loading="savingShipment" @click="saveShipment">登记物流</el-button></el-form-item></el-form></el-col>
        </el-row>
        <el-table :data="detail.shipments" size="small" border><el-table-column label="方向" width="100"><template #default="scope">{{ scope.row.directionCode === 'inbound' ? '收件' : '回寄' }}</template></el-table-column><el-table-column label="承运商" prop="carrierName" width="130" /><el-table-column label="单号" prop="trackingNumber" min-width="200" /><el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="scope.row.statusCode === 'delivered' ? 'success' : 'info'">{{ scope.row.statusCode }}</el-tag></template></el-table-column><el-table-column label="发件时间" width="170"><template #default="scope">{{ parseTime(scope.row.shippedAt) }}</template></el-table-column><el-table-column label="操作" width="110"><template #default="scope"><el-button v-if="!scope.row.deliveredAt" v-hasPermi="['nxr:order:manage','nxr:order:shipping']" link type="success" @click="markDelivered(scope.row)">签收</el-button></template></el-table-column></el-table>

        <el-form v-if="detail.shipments.length" v-hasPermi="['nxr:order:manage','nxr:order:shipping']" :inline="true" :model="trackingForm" class="operation-form">
          <el-form-item label="物流"><el-select v-model="trackingForm.shipmentId" style="width:220px"><el-option v-for="shipment in detail.shipments" :key="shipment.id" :label="`${shipment.directionCode} · ${shipment.carrierName} · ${shipment.trackingNumber}`" :value="shipment.id" /></el-select></el-form-item><el-form-item label="节点"><el-select v-model="trackingForm.eventCode" style="width:150px"><el-option v-for="item in trackingEventOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item><el-form-item label="地点"><el-input v-model="trackingForm.locationLabel" style="width:150px" /></el-form-item><el-form-item label="说明"><el-input v-model="trackingForm.eventDetail" style="width:210px" /></el-form-item><el-form-item><el-button type="primary" :loading="savingTracking" @click="saveTrackingEvent">新增轨迹</el-button></el-form-item>
        </el-form>
        <el-table v-if="operations?.trackingEvents.length" :data="operations.trackingEvents" size="small" border><el-table-column label="时间" width="170"><template #default="scope">{{ parseTime(scope.row.eventTime) }}</template></el-table-column><el-table-column label="方向" prop="directionCode" width="90" /><el-table-column label="节点" min-width="150"><template #default="scope">{{ scope.row.eventTitle }}</template></el-table-column><el-table-column label="地点" prop="locationLabel" width="150" /><el-table-column label="说明" prop="eventDetail" min-width="200" /></el-table>

        <el-divider content-position="left">客服工单与回寄方案变更</el-divider>
        <el-table v-if="operations" :data="operations.tickets" size="small" border class="mb12"><el-table-column label="工单号" prop="ticketNo" width="150" /><el-table-column label="类型" width="120"><template #default="scope">{{ labelStatus(scope.row.categoryCode) }}</template></el-table-column><el-table-column label="主题" prop="subject" min-width="190" /><el-table-column label="状态" width="130"><template #default="scope"><el-tag>{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column><el-table-column label="最新消息" min-width="230"><template #default="scope">{{ scope.row.messages.at(-1)?.message || '-' }}</template></el-table-column><el-table-column label="操作" width="100"><template #default="scope"><el-button v-hasPermi="['nxr:order:manage','nxr:order:support']" link type="primary" @click="openTicketAction(scope.row)">处理</el-button></template></el-table-column></el-table>
        <el-table v-if="operations" :data="operations.shippingChanges" size="small" border><el-table-column label="原方案" prop="oldOptionName" min-width="140" /><el-table-column label="新方案" prop="newOptionName" min-width="140" /><el-table-column label="差价" width="120"><template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.differenceAmount).toFixed(2) }}</template></el-table-column><el-table-column label="状态" width="140"><template #default="scope"><el-tag>{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column><el-table-column label="原因" prop="reason" min-width="200" /><el-table-column label="操作" width="160"><template #default="scope"><el-button v-if="scope.row.statusCode === 'requested'" v-hasPermi="['nxr:order:manage','nxr:order:support']" link type="success" @click="openShippingReview(scope.row, true)">通过</el-button><el-button v-if="scope.row.statusCode === 'requested'" v-hasPermi="['nxr:order:manage','nxr:order:support']" link type="danger" @click="openShippingReview(scope.row, false)">驳回</el-button><el-button v-if="scope.row.statusCode === 'awaiting_settlement'" v-hasPermi="['nxr:order:manage','nxr:order:payment']" link type="primary" @click="openSettlement(scope.row)">登记结算</el-button></template></el-table-column></el-table>

        <el-divider content-position="left">客户可见进度</el-divider>
        <el-timeline><el-timeline-item v-for="event in detail.timeline" :key="event.id" :timestamp="parseTime(event.createdAt)"><strong>{{ event.title }}</strong><p v-if="event.detail" class="timeline-detail">{{ event.detail }}</p></el-timeline-item></el-timeline>
      </template>
    </el-dialog>

    <el-dialog v-model="paymentDialogOpen" :title="paymentAction === 'confirm' ? '确认收款' : '驳回收款'" width="460px" append-to-body>
      <el-form :model="paymentForm" label-width="110px"><el-form-item v-if="paymentAction === 'confirm'" label="交易号"><el-input v-model="paymentForm.providerTransactionId" placeholder="可选" /></el-form-item><el-form-item :label="paymentAction === 'confirm' ? '备注' : '驳回原因'" required><el-input v-model="paymentForm.note" type="textarea" :rows="3" /></el-form-item></el-form>
      <template #footer><el-button @click="paymentDialogOpen = false">取消</el-button><el-button :type="paymentAction === 'confirm' ? 'success' : 'danger'" :loading="savingPayment" @click="savePaymentAction">确认</el-button></template>
    </el-dialog>

    <el-dialog v-model="exceptionDialogOpen" title="处理入库异常" width="500px" append-to-body><el-form :model="exceptionResolutionForm" label-width="90px"><el-form-item label="异常"><span>{{ activeException?.title }}</span></el-form-item><el-form-item label="处理结果" required><el-input v-model="exceptionResolutionForm.resolutionNote" type="textarea" :rows="4" maxlength="4000" show-word-limit /></el-form-item></el-form><template #footer><el-button @click="exceptionDialogOpen=false">取消</el-button><el-button type="primary" :loading="resolvingException" @click="resolveException">确认解决</el-button></template></el-dialog>

    <el-dialog v-model="ticketDialogOpen" title="处理客服工单" width="560px" append-to-body><template v-if="activeTicket"><el-descriptions :column="1" border><el-descriptions-item label="工单">{{ activeTicket.ticketNo }} · {{ activeTicket.subject }}</el-descriptions-item><el-descriptions-item label="历史消息"><div v-for="message in activeTicket.messages" :key="message.id" class="ticket-history"><strong>{{ message.actorTypeCode }}</strong><span>{{ message.message }}</span><small>{{ parseTime(message.createdAt) }}</small></div></el-descriptions-item></el-descriptions><el-form :model="ticketActionForm" label-width="90px" class="mt12"><el-form-item label="状态"><el-select v-model="ticketActionForm.statusCode"><el-option label="已分派" value="assigned" /><el-option label="等待客户" value="waiting_customer" /><el-option label="已解决" value="resolved" /><el-option label="已关闭" value="closed" /></el-select></el-form-item><el-form-item label="回复"><el-input v-model="ticketActionForm.message" type="textarea" :rows="3" maxlength="4000" /></el-form-item><el-form-item label="附件引用"><el-input v-model="ticketActionForm.attachmentReference" maxlength="512" /></el-form-item></el-form></template><template #footer><el-button @click="ticketDialogOpen=false">取消</el-button><el-button type="primary" :loading="savingTicket" @click="saveTicketAction">保存</el-button></template></el-dialog>

    <el-dialog v-model="shippingActionDialogOpen" :title="shippingActionMode === 'settle' ? '登记物流差价结算' : (shippingReviewApproved ? '通过回寄变更' : '驳回回寄变更')" width="520px" append-to-body><el-form :model="shippingActionForm" label-width="100px"><el-form-item v-if="shippingActionMode === 'settle'" label="交易/退款号"><el-input v-model="shippingActionForm.providerTransactionId" maxlength="255" /></el-form-item><el-form-item label="处理说明" required><el-input v-model="shippingActionForm.note" type="textarea" :rows="4" maxlength="2000" /></el-form-item></el-form><template #footer><el-button @click="shippingActionDialogOpen=false">取消</el-button><el-button type="primary" :loading="savingShippingAction" @click="saveShippingAction">确认</el-button></template></el-dialog>

    <el-dialog v-model="shippingConfigOpen" title="评级与回寄价格配置" width="920px" append-to-body>
      <el-divider content-position="left">基础评级服务</el-divider>
      <el-form :inline="true" :model="servicePriceForm" class="service-price-form">
        <el-form-item label="服务名称" required><el-input v-model="servicePriceForm.displayName" maxlength="128" /></el-form-item>
        <el-form-item label="币种" required><el-input v-model="servicePriceForm.currencyCode" maxlength="3" style="width:90px" /></el-form-item>
        <el-form-item label="每张单价" required><el-input-number v-model="servicePriceForm.unitPrice" :min="0" :precision="2" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="savingServicePrice" @click="saveServicePriceConfig">保存评级单价</el-button></el-form-item>
      </el-form>
      <el-alert :closable="false" type="info" show-icon :title="`当前价格版本 v${servicePriceForm.versionNo || 1}；新价格只影响新订单，历史订单金额快照不会改变。`" />
      <el-divider content-position="left">回寄方案</el-divider>
      <el-table :data="shippingConfigRows" size="small" border><el-table-column label="编码" prop="optionCode" min-width="150" /><el-table-column label="名称" prop="displayName" min-width="170" /><el-table-column label="适用国家" prop="countryScope" min-width="150" /><el-table-column label="价格" width="120"><template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.priceAmount).toFixed(2) }}</template></el-table-column><el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="scope.row.active ? 'success' : 'info'">{{ scope.row.active ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="90"><template #default="scope"><el-button link type="primary" @click="editShippingOption(scope.row)">编辑</el-button></template></el-table-column></el-table>
      <el-divider content-position="left">{{ shippingConfigForm.id ? '编辑方案' : '新增方案' }}</el-divider>
      <el-form :model="shippingConfigForm" label-width="100px"><el-row :gutter="12"><el-col :span="8"><el-form-item label="方案编码" required><el-input v-model="shippingConfigForm.optionCode" :disabled="Boolean(shippingConfigForm.id)" /></el-form-item></el-col><el-col :span="8"><el-form-item label="显示名称" required><el-input v-model="shippingConfigForm.displayName" /></el-form-item></el-col><el-col :span="8"><el-form-item label="排序"><el-input-number v-model="shippingConfigForm.sortOrder" :min="0" /></el-form-item></el-col><el-col :span="12"><el-form-item label="适用国家" required><el-input v-model="shippingConfigForm.countryScope" placeholder="* 或 US,CA,CN" /></el-form-item></el-col><el-col :span="6"><el-form-item label="币种" required><el-input v-model="shippingConfigForm.currencyCode" maxlength="3" /></el-form-item></el-col><el-col :span="6"><el-form-item label="价格" required><el-input-number v-model="shippingConfigForm.priceAmount" :min="0" :precision="2" /></el-form-item></el-col><el-col :span="24"><el-form-item label="说明"><el-input v-model="shippingConfigForm.description" maxlength="512" /></el-form-item></el-col><el-col :span="8"><el-form-item label="启用"><el-switch v-model="shippingConfigForm.active" /></el-form-item></el-col></el-row></el-form>
      <template #footer><el-button @click="resetShippingConfigForm">清空</el-button><el-button type="primary" :loading="savingShippingConfig" @click="saveShippingConfig">保存方案</el-button></template>
    </el-dialog>
  </main>
</template>

<script setup name="NxrOrders">
import NxrPageHeader from '@/components/NxrWorkspace/PageHeader.vue'
import {
  addShipmentTrackingEvent,
  confirmGradingPayment,
  createOrderTask,
  createGradingShipment,
  getGradingOrder,
  getGradingServicePrice,
  getOrderOperations,
  linkGradingOrderItem,
  listGradingOrders,
  listReturnShippingOptions,
  lookupOrderIntake,
  markGradingShipmentDelivered,
  receiveGradingOrder,
  rejectGradingPayment,
  resolveOrderException,
  reviewOrderShippingChange,
  runOrderQualityCheck,
  saveGradingServicePrice,
  saveReturnShippingOption,
  settleOrderShippingChange,
  updateOrderTask,
  updateOrderTicket,
  updateGradingOrderStatus
} from '@/api/nxr/orders'

const { proxy } = getCurrentInstance()
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const detailOpen = ref(false)
const detail = ref(null)
const operations = ref(null)
const intakeLookupCode = ref('')
const lookingUpIntake = ref(false)
const receivingOrder = ref(false)
const paymentDialogOpen = ref(false)
const paymentAction = ref('confirm')
const activePayment = ref(null)
const savingPayment = ref(false)
const savingStatus = ref(false)
const savingShipment = ref(false)
const linkingItemId = ref(null)
const creatingTask = ref(false)
const savingTaskId = ref(null)
const savingQuality = ref(false)
const savingTracking = ref(false)
const exceptionDialogOpen = ref(false)
const activeException = ref(null)
const resolvingException = ref(false)
const ticketDialogOpen = ref(false)
const activeTicket = ref(null)
const savingTicket = ref(false)
const shippingActionDialogOpen = ref(false)
const shippingActionMode = ref('review')
const activeShippingChange = ref(null)
const shippingReviewApproved = ref(false)
const savingShippingAction = ref(false)
const shippingConfigOpen = ref(false)
const shippingConfigRows = ref([])
const savingShippingConfig = ref(false)
const savingServicePrice = ref(false)
const submissionLinks = reactive({})
const taskDrafts = reactive({})
const queryParams = reactive({ page: 1, pageSize: 20, status: undefined, query: undefined })
const statusForm = reactive({ statusCode: '' })
const shipmentForm = reactive({ direction: 'inbound', carrierName: '', trackingNumber: '', note: '' })
const paymentForm = reactive({ providerTransactionId: '', note: '' })
const intakeForm = reactive({ intakeCode: '', packageNo: '', receivedCount: 0, conditionNote: '', exceptionTypes: [] })
const taskForm = reactive({ taskTypeCode: 'preprocess', orderItemId: null })
const qualityForm = reactive({ passed: true, note: '' })
const trackingForm = reactive({ shipmentId: null, eventCode: 'in_transit', locationLabel: '', eventDetail: '' })
const exceptionResolutionForm = reactive({ resolutionNote: '' })
const ticketActionForm = reactive({ statusCode: 'assigned', message: '', attachmentReference: '' })
const shippingActionForm = reactive({ providerTransactionId: '', note: '' })
const shippingConfigForm = reactive(emptyShippingConfig())
const servicePriceForm = reactive({ displayName: '', unitPrice: 0, currencyCode: 'USD', versionNo: 1 })

const taskTypeOptions = [
  { value: 'preprocess', label: '卡片预处理' }, { value: 'vision', label: '机器视觉检测' },
  { value: 'manual_review', label: '人工复审' }, { value: 'encapsulation', label: '标准封装' }
]
const trackingEventOptions = [
  { value: 'label_created', label: '面单已创建' }, { value: 'picked_up', label: '已揽收' },
  { value: 'in_transit', label: '运输中' }, { value: 'customs', label: '清关中' },
  { value: 'exception', label: '物流异常' }, { value: 'out_for_delivery', label: '派送中' },
  { value: 'delivered', label: '已签收' }
]

const statusOptions = [
  { value: 'awaiting_payment', label: '待付款' }, { value: 'payment_review', label: '收款审核' }, { value: 'awaiting_inbound', label: '待寄卡' }, { value: 'inbound_shipped', label: '寄往 NXR' }, { value: 'intake_exception', label: '入库异常' }, { value: 'received', label: '已收卡' }, { value: 'grading', label: '评分中' }, { value: 'review', label: '复核中' }, { value: 'quality_check', label: '终检中' }, { value: 'quality_hold', label: '质检返工' }, { value: 'completed', label: '待回寄' }, { value: 'return_shipped', label: '已回寄' }, { value: 'delivered', label: '已签收' }, { value: 'cancelled', label: '已取消' }
]

const extraStatusLabels = {
  shortage: '少件', overage: '多件', damaged: '破损', missing_barcode: '缺少条码', other: '其他',
  open: '待处理', assigned: '已分派', waiting_customer: '等待客户', resolved: '已解决', closed: '已关闭',
  shipping_change: '物流变更', score_dispute: '分数异议', inquiry: '咨询', requested: '待审核',
  awaiting_settlement: '待结算', settled: '已结算', rejected: '已驳回', pending: '待处理',
  in_progress: '进行中', failed: '失败', completed: '已完成'
}

function labelStatus(value) { return statusOptions.find((item) => item.value === value)?.label || extraStatusLabels[value] || value }
function taskLabel(value) { return taskTypeOptions.find((item) => item.value === value)?.label || value }
function statusType(value) { if (['completed', 'delivered'].includes(value)) return 'success'; if (['cancelled'].includes(value)) return 'danger'; if (['payment_review', 'review'].includes(value)) return 'warning'; return 'info' }
function paymentType(value) { return value === 'confirmed' ? 'success' : value === 'rejected' ? 'danger' : value === 'proof_submitted' ? 'warning' : 'info' }
function canReviewPayment(payment) { return ['pending', 'proof_submitted'].includes(payment.statusCode) }

function loadOrders(resetPage = false) {
  if (resetPage) queryParams.page = 1
  loading.value = true
  return listGradingOrders(queryParams).then((res) => { rows.value = res.data.items; total.value = res.data.total; queryParams.page = res.data.page; queryParams.pageSize = res.data.pageSize }).finally(() => { loading.value = false })
}

function resetQuery() { proxy.resetForm('queryRef'); loadOrders(true) }

async function openDetail(orderId) {
  const [detailResponse, operationsResponse] = await Promise.all([getGradingOrder(orderId), getOrderOperations(orderId)])
  detail.value = detailResponse.data
  operations.value = operationsResponse.data
  statusForm.statusCode = detail.value.statusCode
  intakeForm.intakeCode = operations.value.intakeCode || ''
  intakeForm.receivedCount = operations.value.expectedCardCount
  intakeForm.packageNo = ''
  intakeForm.conditionNote = ''
  intakeForm.exceptionTypes = []
  trackingForm.shipmentId = detail.value.shipments[0]?.id || null
  Object.keys(taskDrafts).forEach((key) => delete taskDrafts[key])
  operations.value.workTasks.forEach((task) => {
    taskDrafts[task.id] = {
      statusCode: task.statusCode,
      summary: task.statusCode === 'failed' ? (task.failureReason || '') : (task.resultSummary || '')
    }
  })
  detailOpen.value = true
}

function refreshDetail() { if (!detail.value) return Promise.resolve(); return openDetail(detail.value.id).then(() => loadOrders()) }

function openPaymentAction(payment, action) { activePayment.value = payment; paymentAction.value = action; paymentForm.providerTransactionId = ''; paymentForm.note = ''; paymentDialogOpen.value = true }

function savePaymentAction() {
  if (!detail.value || !activePayment.value) return
  if (paymentAction.value === 'reject' && !paymentForm.note.trim()) { proxy.$modal.msgWarning('请填写驳回原因'); return }
  savingPayment.value = true
  const call = paymentAction.value === 'confirm' ? confirmGradingPayment : rejectGradingPayment
  const payload = paymentAction.value === 'confirm' ? { providerTransactionId: paymentForm.providerTransactionId, note: paymentForm.note } : { note: paymentForm.note }
  call(detail.value.id, activePayment.value.id, payload).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; paymentDialogOpen.value = false; proxy.$modal.msgSuccess(paymentAction.value === 'confirm' ? '收款已确认' : '收款已驳回'); return loadOrders() }).finally(() => { savingPayment.value = false })
}

function saveStatus() {
  if (!detail.value || !statusForm.statusCode) return
  savingStatus.value = true
  updateGradingOrderStatus(detail.value.id, { statusCode: statusForm.statusCode, detail: '' }).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; proxy.$modal.msgSuccess('订单状态已更新'); return loadOrders() }).finally(() => { savingStatus.value = false })
}

function saveShipment() {
  if (!detail.value || !shipmentForm.carrierName.trim() || !shipmentForm.trackingNumber.trim()) { proxy.$modal.msgWarning('请填写承运商和物流单号'); return }
  savingShipment.value = true
  createGradingShipment(detail.value.id, shipmentForm).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; shipmentForm.carrierName = ''; shipmentForm.trackingNumber = ''; shipmentForm.note = ''; proxy.$modal.msgSuccess('物流已登记'); return loadOrders() }).finally(() => { savingShipment.value = false })
}

function markDelivered(shipment) {
  if (!detail.value) return
  proxy.$modal.confirm('确认该物流已签收？').then(() => markGradingShipmentDelivered(detail.value.id, shipment.id)).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; proxy.$modal.msgSuccess('已更新签收状态'); return loadOrders() }).catch(() => {})
}

function linkSubmission(item) {
  if (!detail.value || !submissionLinks[item.id]) { proxy.$modal.msgWarning('请输入评分条目 ID'); return }
  linkingItemId.value = item.id
  linkGradingOrderItem(detail.value.id, item.id, submissionLinks[item.id]).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; proxy.$modal.msgSuccess('已关联评分条目') }).finally(() => { linkingItemId.value = null })
}

async function lookupIntakeOrder() {
  if (!intakeLookupCode.value.trim()) { proxy.$modal.msgWarning('请输入或扫描入库码'); return }
  lookingUpIntake.value = true
  try {
    const response = await lookupOrderIntake(intakeLookupCode.value.trim())
    await openDetail(response.data.orderId)
    intakeForm.intakeCode = response.data.intakeCode
  } finally {
    lookingUpIntake.value = false
  }
}

async function receiveOrder() {
  if (!detail.value || !intakeForm.intakeCode.trim()) { proxy.$modal.msgWarning('缺少入库码'); return }
  receivingOrder.value = true
  try {
    await receiveGradingOrder(detail.value.id, intakeForm)
    proxy.$modal.msgSuccess('扫码收件和实物清点已记录')
    intakeForm.packageNo = ''
    intakeForm.conditionNote = ''
    intakeForm.exceptionTypes = []
    await refreshDetail()
  } finally {
    receivingOrder.value = false
  }
}

function openExceptionResolution(exception) {
  activeException.value = exception
  exceptionResolutionForm.resolutionNote = ''
  exceptionDialogOpen.value = true
}

async function resolveException() {
  if (!detail.value || !activeException.value || !exceptionResolutionForm.resolutionNote.trim()) { proxy.$modal.msgWarning('请填写处理结果'); return }
  resolvingException.value = true
  try {
    const response = await resolveOrderException(detail.value.id, activeException.value.id, exceptionResolutionForm)
    operations.value = response.data
    exceptionDialogOpen.value = false
    proxy.$modal.msgSuccess('异常已解决')
    await refreshDetail()
  } finally {
    resolvingException.value = false
  }
}

async function createTask() {
  if (!detail.value || !taskForm.taskTypeCode) return
  creatingTask.value = true
  try {
    await createOrderTask(detail.value.id, taskForm)
    proxy.$modal.msgSuccess('作业任务已创建')
    await refreshDetail()
  } finally {
    creatingTask.value = false
  }
}

async function saveTask(task) {
  if (!detail.value) return
  const draft = taskDrafts[task.id]
  savingTaskId.value = task.id
  try {
    await updateOrderTask(detail.value.id, task.id, {
      statusCode: draft.statusCode,
      resultSummary: draft.statusCode === 'failed' ? '' : draft.summary,
      failureReason: draft.statusCode === 'failed' ? draft.summary : ''
    })
    proxy.$modal.msgSuccess('作业进度已保存')
    await refreshDetail()
  } finally {
    savingTaskId.value = null
  }
}

async function saveQualityCheck() {
  if (!detail.value) return
  if (!qualityForm.passed && !qualityForm.note.trim()) { proxy.$modal.msgWarning('返工时必须填写问题说明'); return }
  savingQuality.value = true
  try {
    await runOrderQualityCheck(detail.value.id, qualityForm)
    proxy.$modal.msgSuccess(qualityForm.passed ? '终检合格，订单已进入待回寄' : '已转入返工')
    qualityForm.note = ''
    await refreshDetail()
  } finally {
    savingQuality.value = false
  }
}

async function saveTrackingEvent() {
  if (!detail.value || !trackingForm.shipmentId) { proxy.$modal.msgWarning('请选择物流记录'); return }
  savingTracking.value = true
  try {
    await addShipmentTrackingEvent(detail.value.id, trackingForm.shipmentId, {
      eventCode: trackingForm.eventCode,
      eventTitle: '',
      locationLabel: trackingForm.locationLabel,
      eventDetail: trackingForm.eventDetail,
      eventTime: null
    })
    trackingForm.locationLabel = ''
    trackingForm.eventDetail = ''
    proxy.$modal.msgSuccess('物流轨迹已更新')
    await refreshDetail()
  } finally {
    savingTracking.value = false
  }
}

function openTicketAction(ticket) {
  activeTicket.value = ticket
  ticketActionForm.statusCode = ticket.statusCode === 'open' ? 'assigned' : ticket.statusCode
  ticketActionForm.message = ''
  ticketActionForm.attachmentReference = ''
  ticketDialogOpen.value = true
}

async function saveTicketAction() {
  if (!detail.value || !activeTicket.value) return
  savingTicket.value = true
  try {
    await updateOrderTicket(detail.value.id, activeTicket.value.id, ticketActionForm)
    ticketDialogOpen.value = false
    proxy.$modal.msgSuccess('工单已更新')
    await refreshDetail()
  } finally {
    savingTicket.value = false
  }
}

function openShippingReview(change, approved) {
  activeShippingChange.value = change
  shippingActionMode.value = 'review'
  shippingReviewApproved.value = approved
  shippingActionForm.providerTransactionId = ''
  shippingActionForm.note = ''
  shippingActionDialogOpen.value = true
}

function openSettlement(change) {
  activeShippingChange.value = change
  shippingActionMode.value = 'settle'
  shippingActionForm.providerTransactionId = ''
  shippingActionForm.note = ''
  shippingActionDialogOpen.value = true
}

async function saveShippingAction() {
  if (!detail.value || !activeShippingChange.value || !shippingActionForm.note.trim()) { proxy.$modal.msgWarning('请填写处理说明'); return }
  savingShippingAction.value = true
  try {
    if (shippingActionMode.value === 'settle') {
      await settleOrderShippingChange(detail.value.id, activeShippingChange.value.id, shippingActionForm)
    } else {
      await reviewOrderShippingChange(detail.value.id, activeShippingChange.value.id, {
        approved: shippingReviewApproved.value,
        note: shippingActionForm.note
      })
    }
    shippingActionDialogOpen.value = false
    proxy.$modal.msgSuccess('回寄方案变更已处理')
    await refreshDetail()
  } finally {
    savingShippingAction.value = false
  }
}

function emptyShippingConfig() {
  return { id: null, optionCode: '', displayName: '', description: '', countryScope: '*', currencyCode: 'USD', priceAmount: 0, sortOrder: 0, active: true }
}

async function loadShippingConfig() {
  const [shippingResponse, servicePriceResponse] = await Promise.all([listReturnShippingOptions(), getGradingServicePrice()])
  shippingConfigRows.value = shippingResponse.data
  Object.assign(servicePriceForm, servicePriceResponse.data)
}

async function openShippingConfig() {
  shippingConfigOpen.value = true
  resetShippingConfigForm()
  await loadShippingConfig()
}

function editShippingOption(option) {
  Object.assign(shippingConfigForm, option)
}

function resetShippingConfigForm() {
  Object.assign(shippingConfigForm, emptyShippingConfig())
}

async function saveShippingConfig() {
  if (!shippingConfigForm.optionCode.trim() || !shippingConfigForm.displayName.trim()) { proxy.$modal.msgWarning('请填写方案编码和名称'); return }
  savingShippingConfig.value = true
  try {
    await saveReturnShippingOption(shippingConfigForm.id, shippingConfigForm)
    proxy.$modal.msgSuccess('回寄方案已保存；历史订单价格快照不会改变')
    resetShippingConfigForm()
    await loadShippingConfig()
  } finally {
    savingShippingConfig.value = false
  }
}

async function saveServicePriceConfig() {
  if (!servicePriceForm.displayName.trim() || !servicePriceForm.currencyCode.trim()) { proxy.$modal.msgWarning('请填写服务名称和币种'); return }
  savingServicePrice.value = true
  try {
    const response = await saveGradingServicePrice(servicePriceForm)
    Object.assign(servicePriceForm, response.data)
    proxy.$modal.msgSuccess('评级单价已保存；历史订单金额快照不会改变')
  } finally {
    savingServicePrice.value = false
  }
}

loadOrders()
</script>

<style scoped>
small,.timeline-detail{color:var(--nxr-text-faint)}
.service-price-form{display:flex;align-items:flex-end;gap:4px;flex-wrap:wrap}
.link-submission{display:flex;align-items:center;gap:8px}
.link-submission .el-input-number{width:120px}
.timeline-detail{margin:5px 0 0;line-height:1.5}
.intake-lookup-row{display:flex;align-items:center;gap:12px}
.intake-lookup-row .el-input{max-width:360px}
.operation-form{margin:12px 0}
.quality-form{padding-top:14px;border-top:1px solid var(--nxr-border-subtle)}
.ticket-history{display:grid;grid-template-columns:80px 1fr auto;gap:10px;padding:8px 0;border-bottom:1px solid var(--nxr-border-subtle)}
.ticket-history:last-child{border-bottom:0}
.ticket-history small{white-space:nowrap}
@media (max-width:760px){.intake-lookup-row{align-items:stretch;flex-direction:column}.ticket-history{grid-template-columns:1fr}.ticket-history small{white-space:normal}}
</style>
