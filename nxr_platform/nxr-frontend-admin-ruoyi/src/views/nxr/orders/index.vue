<template>
  <main class="nxr-workspace nxr-orders-workspace">
    <nxr-page-header
      :kicker="$tx('GRADING ORDERS')"
      :title="$tx('Order Management')"
      :summary="$tx('Manage payments, warehouse intake, grading, quality checks, return shipping, adjustments, and support tickets')"
    >
      <template #actions>
        <el-button v-hasPermi="['nxr:order:manage','nxr:order:config']" icon="Setting" @click="openShippingConfig">{{ $tx('Pricing & Return Shipping') }}</el-button>
      </template>
    </nxr-page-header>

    <el-card v-hasPermi="['nxr:order:manage','nxr:order:warehouse']" shadow="never" class="intake-lookup-card mb12">
      <div class="intake-lookup-row"><strong>{{ $tx('Warehouse Intake Scan') }}</strong><el-input v-model="intakeLookupCode" clearable :placeholder="$tx('Scan or enter an intake code')" @keyup.enter="lookupIntakeOrder" /><el-button type="primary" icon="Search" :loading="lookingUpIntake" @click="lookupIntakeOrder">{{ $tx('Find Order') }}</el-button></div>
    </el-card>

    <el-form ref="queryRef" :model="queryParams" :inline="true" @submit.prevent>
      <el-form-item :label="$tx('Order Status')" prop="status">
        <el-select v-model="queryParams.status" clearable :placeholder="$tx('All Statuses')" style="width: 180px">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item :label="$tx('Keyword')" prop="query">
        <el-input v-model="queryParams.query" clearable :placeholder="$tx('Order No. / Email / Customer')" style="width: 260px" @keyup.enter="loadOrders(true)" />
      </el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="loadOrders(true)">{{ $tx('Search') }}</el-button><el-button icon="Refresh" @click="resetQuery">{{ $tx('Reset') }}</el-button></el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows">
      <el-table-column :label="$tx('Order No.')" prop="orderNo" min-width="150" />
      <el-table-column :label="$tx('Customer')" min-width="190" show-overflow-tooltip><template #default="scope"><div>{{ scope.row.customer.displayName }}</div><small>{{ scope.row.customer.email }}</small></template></el-table-column>
      <el-table-column :label="$tx('Cards')" prop="totalCardCount" width="80" align="center" />
      <el-table-column :label="$tx('Service')" prop="serviceLevelCode" width="105" align="center" />
      <el-table-column :label="$tx('Amount')" width="110" align="right"><template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.totalAmount).toFixed(2) }}</template></el-table-column>
      <el-table-column :label="$tx('Status')" width="150" align="center"><template #default="scope"><el-tag :type="statusType(scope.row.statusCode)">{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column>
      <el-table-column :label="$tx('Created At')" width="170" align="center"><template #default="scope">{{ parseTime(scope.row.createdAt) }}</template></el-table-column>
      <el-table-column :label="$tx('Actions')" width="100" align="center"><template #default="scope"><el-button link type="primary" icon="View" @click="openDetail(scope.row.id)">{{ $tx('View') }}</el-button></template></el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.page" v-model:limit="queryParams.pageSize" @pagination="loadOrders" />

    <el-dialog v-model="detailOpen" :title="$tx('Grading Order Details')" width="1080px" append-to-body>
      <template v-if="detail">
        <el-descriptions :column="3" border class="mb12">
          <el-descriptions-item :label="$tx('Order No.')">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Order Status')"><el-tag :type="statusType(detail.statusCode)">{{ labelStatus(detail.statusCode) }}</el-tag></el-descriptions-item>
          <el-descriptions-item :label="$tx('Service / Cards')">{{ detail.serviceLevelCode }} / {{ detail.totalCardCount }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Customer')" :span="2">{{ detail.customer.displayName }} · {{ detail.customer.email }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Phone')">{{ detail.contactPhone }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Return Address')" :span="3">{{ [detail.returnAddressLine1, detail.returnAddressLine2, detail.returnCity, detail.returnRegion, detail.returnPostalCode, detail.returnCountry].filter(Boolean).join(', ') }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Amount')">{{ detail.currencyCode }} {{ Number(detail.totalAmount).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Service Fee')">{{ detail.currencyCode }} {{ Number(detail.serviceFee).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Return Shipping')">{{ detail.currencyCode }} {{ Number(detail.returnShippingFee).toFixed(2) }}</el-descriptions-item>
          <el-descriptions-item :label="$tx('Customer Note')" :span="3">{{ detail.customerNote || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">{{ $tx('Warehouse Intake & Exceptions') }}</el-divider>
        <el-form v-if="operations" v-hasPermi="['nxr:order:manage','nxr:order:warehouse']" :inline="true" :model="intakeForm" class="operation-form">
          <el-form-item :label="$tx('Intake Code')"><el-input v-model="intakeForm.intakeCode" style="width:180px" /></el-form-item>
          <el-form-item :label="$tx('Package No.')"><el-input v-model="intakeForm.packageNo" style="width:150px" /></el-form-item>
          <el-form-item :label="$tx('Cards Received')"><el-input-number v-model="intakeForm.receivedCount" :min="0" :max="1000" /></el-form-item>
          <el-form-item :label="$tx('Exceptions')"><el-select v-model="intakeForm.exceptionTypes" multiple clearable style="width:220px"><el-option :label="$tx('Damaged')" value="damaged" /><el-option :label="$tx('Missing Barcode')" value="missing_barcode" /><el-option :label="$tx('Other')" value="other" /></el-select></el-form-item>
          <el-form-item :label="$tx('Note')"><el-input v-model="intakeForm.conditionNote" style="width:220px" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="receivingOrder" @click="receiveOrder">{{ $tx('Confirm Intake') }}</el-button></el-form-item>
        </el-form>
        <el-empty v-if="operations && !operations.receipts.length && !operations.exceptions.length" :description="$tx('No intake records')" :image-size="58" />
        <el-table v-if="operations?.receipts.length" :data="operations.receipts" size="small" border class="mb12">
          <el-table-column :label="$tx('Received At')" width="170"><template #default="scope">{{ parseTime(scope.row.receivedAt) }}</template></el-table-column><el-table-column :label="$tx('Package No.')" prop="packageNo" min-width="150" /><el-table-column :label="$tx('Expected')" prop="expectedCount" width="80" align="center" /><el-table-column :label="$tx('Received')" prop="receivedCount" width="80" align="center" /><el-table-column :label="$tx('Receiver ID')" prop="receivedByUserId" width="100" align="center" /><el-table-column :label="$tx('Note')" prop="conditionNote" min-width="180" />
        </el-table>
        <el-table v-if="operations?.exceptions.length" :data="operations.exceptions" size="small" border>
          <el-table-column :label="$tx('Exception')" width="130"><template #default="scope">{{ labelStatus(scope.row.exceptionTypeCode) }}</template></el-table-column><el-table-column :label="$tx('Title')" prop="title" min-width="170" /><el-table-column :label="$tx('Details')" prop="detail" min-width="220" /><el-table-column :label="$tx('Status')" width="100"><template #default="scope"><el-tag :type="scope.row.statusCode === 'resolved' ? 'success' : 'danger'">{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column><el-table-column :label="$tx('Resolution')" prop="resolutionNote" min-width="180" /><el-table-column :label="$tx('Actions')" width="100"><template #default="scope"><el-button v-if="scope.row.statusCode !== 'resolved'" v-hasPermi="['nxr:order:manage','nxr:order:warehouse','nxr:order:support']" link type="primary" @click="openExceptionResolution(scope.row)">{{ $tx('Resolve') }}</el-button></template></el-table-column>
        </el-table>

        <el-divider content-position="left">{{ $tx('Order Cards & Grading Links') }}</el-divider>
        <el-table :data="detail.items" size="small" border>
          <el-table-column label="#" prop="itemNo" width="55" align="center" />
          <el-table-column :label="$tx('Card Name')" prop="cardName" min-width="180" />
          <el-table-column :label="$tx('Details')" min-width="180"><template #default="scope">{{ [scope.row.brandName, scope.row.setName, scope.row.cardNumber, scope.row.languageCode].filter(Boolean).join(' · ') || '-' }}</template></el-table-column>
          <el-table-column :label="$tx('Item Status')" width="130"><template #default="scope"><el-tag>{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column>
          <el-table-column :label="$tx('Grading Entry')" min-width="220"><template #default="scope"><span v-if="scope.row.gradingSubmissionId">#{{ scope.row.gradingSubmissionId }} · {{ scope.row.gradingCertId }} · {{ scope.row.gradingStatusCode }}</span><div v-else class="link-submission"><el-input-number v-model="submissionLinks[scope.row.id]" :min="1" :controls="false" :placeholder="$tx('Entry ID')" /><el-button v-hasPermi="['nxr:order:manage','nxr:order:grading']" link type="primary" :loading="linkingItemId === scope.row.id" @click="linkSubmission(scope.row)">{{ $tx('Link') }}</el-button></div></template></el-table-column>
        </el-table>

        <el-divider content-position="left">{{ $tx('Internal Tasks & Quality Check') }}</el-divider>
        <template v-if="operations">
          <el-form v-hasPermi="['nxr:order:manage','nxr:order:grading']" :inline="true" :model="taskForm" class="operation-form">
            <el-form-item :label="$tx('Task Type')"><el-select v-model="taskForm.taskTypeCode" style="width:170px"><el-option v-for="item in taskTypeOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            <el-form-item :label="$tx('Order Card')"><el-select v-model="taskForm.orderItemId" clearable :placeholder="$tx('Whole order')" style="width:190px"><el-option v-for="item in detail.items" :key="item.id" :label="`${item.itemNo}. ${item.cardName}`" :value="item.id" /></el-select></el-form-item>
            <el-form-item><el-button type="primary" :loading="creatingTask" @click="createTask">{{ $tx('New Task') }}</el-button></el-form-item>
          </el-form>
          <el-table :data="operations.workTasks" size="small" border>
            <el-table-column :label="$tx('Task')" width="150"><template #default="scope">{{ taskLabel(scope.row.taskTypeCode) }}</template></el-table-column><el-table-column :label="$tx('Card ID')" prop="orderItemId" width="90" align="center" /><el-table-column :label="$tx('Status')" width="150"><template #default="scope"><el-select v-model="taskDrafts[scope.row.id].statusCode" size="small"><el-option :label="$tx('Pending')" value="pending" /><el-option :label="$tx('In Progress')" value="in_progress" /><el-option :label="$tx('Completed')" value="completed" /><el-option :label="$tx('Failed / Retry')" value="failed" /></el-select></template></el-table-column><el-table-column :label="$tx('Attempts')" prop="attemptCount" width="90" align="center" /><el-table-column :label="$tx('Result / Failure Reason')" min-width="240"><template #default="scope"><el-input v-model="taskDrafts[scope.row.id].summary" size="small" :placeholder="taskDrafts[scope.row.id].statusCode === 'failed' ? $tx('Enter failure reason') : $tx('Enter task result')" /></template></el-table-column><el-table-column :label="$tx('Actions')" width="100"><template #default="scope"><el-button v-hasPermi="['nxr:order:manage','nxr:order:grading']" link type="primary" :loading="savingTaskId === scope.row.id" @click="saveTask(scope.row)">{{ $tx('Save') }}</el-button></template></el-table-column>
          </el-table>
          <el-form v-hasPermi="['nxr:order:manage','nxr:order:grading']" :inline="true" :model="qualityForm" class="operation-form quality-form"><el-form-item :label="$tx('QC Result')"><el-radio-group v-model="qualityForm.passed"><el-radio-button :value="true">{{ $tx('Pass') }}</el-radio-button><el-radio-button :value="false">{{ $tx('Rework') }}</el-radio-button></el-radio-group></el-form-item><el-form-item :label="$tx('QC Note')"><el-input v-model="qualityForm.note" style="width:300px" /></el-form-item><el-form-item><el-button type="success" :loading="savingQuality" @click="saveQualityCheck">{{ $tx('Submit QC') }}</el-button></el-form-item></el-form>
        </template>

        <el-divider content-position="left">{{ $tx('Payments & Transactions') }}</el-divider>
        <el-table :data="detail.payments" size="small" border>
          <el-table-column :label="$tx('Type')" width="120"><template #default="scope">{{ scope.row.paymentTypeCode }}</template></el-table-column>
          <el-table-column :label="$tx('Amount')" width="130"><template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.amount).toFixed(2) }}</template></el-table-column>
          <el-table-column :label="$tx('Provider')" min-width="140"><template #default="scope">{{ scope.row.providerCode }}</template></el-table-column>
          <el-table-column :label="$tx('Payment Reference')" min-width="180"><template #default="scope">{{ scope.row.payerReference || '-' }}</template></el-table-column>
          <el-table-column :label="$tx('Status')" width="140"><template #default="scope"><el-tag :type="paymentType(scope.row.statusCode)">{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column>
          <el-table-column :label="$tx('Actions')" width="150"><template #default="scope"><el-button v-if="canReviewPayment(scope.row)" v-hasPermi="['nxr:order:manage','nxr:order:payment']" link type="success" @click="openPaymentAction(scope.row, 'confirm')">{{ $tx('Confirm') }}</el-button><el-button v-if="canReviewPayment(scope.row)" v-hasPermi="['nxr:order:manage','nxr:order:payment']" link type="danger" @click="openPaymentAction(scope.row, 'reject')">{{ $tx('Reject') }}</el-button></template></el-table-column>
        </el-table>

        <el-divider content-position="left">{{ $tx('Shipping & Progress') }}</el-divider>
        <el-row :gutter="16" class="mb12">
          <el-col v-hasPermi="['nxr:order:manage']" :span="12"><el-form :inline="true" :model="statusForm"><el-form-item :label="$tx('Advance Status')"><el-select v-model="statusForm.statusCode" style="width: 180px"><el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item><el-form-item><el-button type="primary" :loading="savingStatus" @click="saveStatus">{{ $tx('Save') }}</el-button></el-form-item></el-form></el-col>
          <el-col v-hasPermi="['nxr:order:manage','nxr:order:shipping']" :span="12"><el-form :inline="true" :model="shipmentForm"><el-form-item :label="$tx('Direction')"><el-select v-model="shipmentForm.direction" style="width: 100px"><el-option :label="$tx('Inbound')" value="inbound" /><el-option :label="$tx('Outbound')" value="outbound" /></el-select></el-form-item><el-form-item :label="$tx('Carrier')"><el-input v-model="shipmentForm.carrierName" style="width: 120px" /></el-form-item><el-form-item :label="$tx('Tracking No.')"><el-input v-model="shipmentForm.trackingNumber" style="width: 150px" /></el-form-item><el-form-item><el-button type="primary" :loading="savingShipment" @click="saveShipment">{{ $tx('Add Shipment') }}</el-button></el-form-item></el-form></el-col>
        </el-row>
        <el-table :data="detail.shipments" size="small" border><el-table-column :label="$tx('Direction')" width="100"><template #default="scope">{{ scope.row.directionCode === 'inbound' ? $tx('Inbound') : $tx('Outbound') }}</template></el-table-column><el-table-column :label="$tx('Carrier')" prop="carrierName" width="130" /><el-table-column :label="$tx('Tracking No.')" prop="trackingNumber" min-width="200" /><el-table-column :label="$tx('Status')" width="120"><template #default="scope"><el-tag :type="scope.row.statusCode === 'delivered' ? 'success' : 'info'">{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column><el-table-column :label="$tx('Shipped At')" width="170"><template #default="scope">{{ parseTime(scope.row.shippedAt) }}</template></el-table-column><el-table-column :label="$tx('Actions')" width="110"><template #default="scope"><el-button v-if="!scope.row.deliveredAt" v-hasPermi="['nxr:order:manage','nxr:order:shipping']" link type="success" @click="markDelivered(scope.row)">{{ $tx('Mark Delivered') }}</el-button></template></el-table-column></el-table>

        <el-form v-if="detail.shipments.length" v-hasPermi="['nxr:order:manage','nxr:order:shipping']" :inline="true" :model="trackingForm" class="operation-form">
          <el-form-item :label="$tx('Shipment')"><el-select v-model="trackingForm.shipmentId" style="width:220px"><el-option v-for="shipment in detail.shipments" :key="shipment.id" :label="`${shipment.directionCode} · ${shipment.carrierName} · ${shipment.trackingNumber}`" :value="shipment.id" /></el-select></el-form-item><el-form-item :label="$tx('Event')"><el-select v-model="trackingForm.eventCode" style="width:150px"><el-option v-for="item in trackingEventOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item><el-form-item :label="$tx('Location')"><el-input v-model="trackingForm.locationLabel" style="width:150px" /></el-form-item><el-form-item :label="$tx('Details')"><el-input v-model="trackingForm.eventDetail" style="width:210px" /></el-form-item><el-form-item><el-button type="primary" :loading="savingTracking" @click="saveTrackingEvent">{{ $tx('Add Tracking Event') }}</el-button></el-form-item>
        </el-form>
        <el-table v-if="operations?.trackingEvents.length" :data="operations.trackingEvents" size="small" border><el-table-column :label="$tx('Time')" width="170"><template #default="scope">{{ parseTime(scope.row.eventTime) }}</template></el-table-column><el-table-column :label="$tx('Direction')" prop="directionCode" width="90" /><el-table-column :label="$tx('Event')" min-width="150"><template #default="scope">{{ scope.row.eventTitle }}</template></el-table-column><el-table-column :label="$tx('Location')" prop="locationLabel" width="150" /><el-table-column :label="$tx('Details')" prop="eventDetail" min-width="200" /></el-table>

        <el-divider content-position="left">{{ $tx('Support Tickets & Return Shipping Changes') }}</el-divider>
        <el-table v-if="operations" :data="operations.tickets" size="small" border class="mb12"><el-table-column :label="$tx('Ticket No.')" prop="ticketNo" width="150" /><el-table-column :label="$tx('Type')" width="120"><template #default="scope">{{ labelStatus(scope.row.categoryCode) }}</template></el-table-column><el-table-column :label="$tx('Subject')" prop="subject" min-width="190" /><el-table-column :label="$tx('Status')" width="130"><template #default="scope"><el-tag>{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column><el-table-column :label="$tx('Latest Message')" min-width="230"><template #default="scope">{{ scope.row.messages.at(-1)?.message || '-' }}</template></el-table-column><el-table-column :label="$tx('Actions')" width="100"><template #default="scope"><el-button v-hasPermi="['nxr:order:manage','nxr:order:support']" link type="primary" @click="openTicketAction(scope.row)">{{ $tx('Handle') }}</el-button></template></el-table-column></el-table>
        <el-table v-if="operations" :data="operations.shippingChanges" size="small" border><el-table-column :label="$tx('Previous Option')" prop="oldOptionName" min-width="140" /><el-table-column :label="$tx('New Option')" prop="newOptionName" min-width="140" /><el-table-column :label="$tx('Adjustment')" width="120"><template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.differenceAmount).toFixed(2) }}</template></el-table-column><el-table-column :label="$tx('Status')" width="140"><template #default="scope"><el-tag>{{ labelStatus(scope.row.statusCode) }}</el-tag></template></el-table-column><el-table-column :label="$tx('Reason')" prop="reason" min-width="200" /><el-table-column :label="$tx('Actions')" width="190"><template #default="scope"><el-button v-if="scope.row.statusCode === 'requested'" v-hasPermi="['nxr:order:manage','nxr:order:support']" link type="success" @click="openShippingReview(scope.row, true)">{{ $tx('Approve') }}</el-button><el-button v-if="scope.row.statusCode === 'requested'" v-hasPermi="['nxr:order:manage','nxr:order:support']" link type="danger" @click="openShippingReview(scope.row, false)">{{ $tx('Reject') }}</el-button><el-button v-if="scope.row.statusCode === 'awaiting_settlement'" v-hasPermi="['nxr:order:manage','nxr:order:payment']" link type="primary" @click="openSettlement(scope.row)">{{ $tx('Record Settlement') }}</el-button></template></el-table-column></el-table>

        <el-divider content-position="left">{{ $tx('Customer-visible Timeline') }}</el-divider>
        <el-timeline><el-timeline-item v-for="event in detail.timeline" :key="event.id" :timestamp="parseTime(event.createdAt)"><strong>{{ event.title }}</strong><p v-if="event.detail" class="timeline-detail">{{ event.detail }}</p></el-timeline-item></el-timeline>
      </template>
    </el-dialog>

    <el-dialog v-model="paymentDialogOpen" :title="paymentAction === 'confirm' ? $tx('Confirm Payment') : $tx('Reject Payment')" width="460px" append-to-body>
      <el-form :model="paymentForm" label-width="110px"><el-form-item v-if="paymentAction === 'confirm'" :label="$tx('Transaction ID')"><el-input v-model="paymentForm.providerTransactionId" :placeholder="$tx('Optional')" /></el-form-item><el-form-item :label="paymentAction === 'confirm' ? $tx('Note') : $tx('Rejection Reason')" required><el-input v-model="paymentForm.note" type="textarea" :rows="3" /></el-form-item></el-form>
      <template #footer><el-button @click="paymentDialogOpen = false">{{ $tx('Cancel') }}</el-button><el-button :type="paymentAction === 'confirm' ? 'success' : 'danger'" :loading="savingPayment" @click="savePaymentAction">{{ paymentAction === 'confirm' ? $tx('Confirm') : $tx('Reject') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="exceptionDialogOpen" :title="$tx('Resolve Intake Exception')" width="500px" append-to-body><el-form :model="exceptionResolutionForm" label-width="90px"><el-form-item :label="$tx('Exception')"><span>{{ activeException?.title }}</span></el-form-item><el-form-item :label="$tx('Resolution')" required><el-input v-model="exceptionResolutionForm.resolutionNote" type="textarea" :rows="4" maxlength="4000" show-word-limit /></el-form-item></el-form><template #footer><el-button @click="exceptionDialogOpen=false">{{ $tx('Cancel') }}</el-button><el-button type="primary" :loading="resolvingException" @click="resolveException">{{ $tx('Resolve') }}</el-button></template></el-dialog>

    <el-dialog v-model="ticketDialogOpen" :title="$tx('Handle Support Ticket')" width="560px" append-to-body><template v-if="activeTicket"><el-descriptions :column="1" border><el-descriptions-item :label="$tx('Ticket')">{{ activeTicket.ticketNo }} · {{ activeTicket.subject }}</el-descriptions-item><el-descriptions-item :label="$tx('Message History')"><div v-for="message in activeTicket.messages" :key="message.id" class="ticket-history"><strong>{{ message.actorTypeCode }}</strong><span>{{ message.message }}</span><small>{{ parseTime(message.createdAt) }}</small></div></el-descriptions-item></el-descriptions><el-form :model="ticketActionForm" label-width="90px" class="mt12"><el-form-item :label="$tx('Status')"><el-select v-model="ticketActionForm.statusCode"><el-option :label="$tx('Assigned')" value="assigned" /><el-option :label="$tx('Waiting for Customer')" value="waiting_customer" /><el-option :label="$tx('Resolved')" value="resolved" /><el-option :label="$tx('Closed')" value="closed" /></el-select></el-form-item><el-form-item :label="$tx('Reply')"><el-input v-model="ticketActionForm.message" type="textarea" :rows="3" maxlength="4000" /></el-form-item><el-form-item :label="$tx('Attachment Reference')"><el-input v-model="ticketActionForm.attachmentReference" maxlength="512" /></el-form-item></el-form></template><template #footer><el-button @click="ticketDialogOpen=false">{{ $tx('Cancel') }}</el-button><el-button type="primary" :loading="savingTicket" @click="saveTicketAction">{{ $tx('Save') }}</el-button></template></el-dialog>

    <el-dialog v-model="shippingActionDialogOpen" :title="shippingActionMode === 'settle' ? $tx('Record Shipping Adjustment') : (shippingReviewApproved ? $tx('Approve Return Shipping Change') : $tx('Reject Return Shipping Change'))" width="520px" append-to-body><el-form :model="shippingActionForm" label-width="130px"><el-form-item v-if="shippingActionMode === 'settle'" :label="$tx('Transaction / Refund ID')"><el-input v-model="shippingActionForm.providerTransactionId" maxlength="255" /></el-form-item><el-form-item :label="$tx('Processing Note')" required><el-input v-model="shippingActionForm.note" type="textarea" :rows="4" maxlength="2000" /></el-form-item></el-form><template #footer><el-button @click="shippingActionDialogOpen=false">{{ $tx('Cancel') }}</el-button><el-button type="primary" :loading="savingShippingAction" @click="saveShippingAction">{{ $tx('Confirm') }}</el-button></template></el-dialog>

    <el-dialog v-model="shippingConfigOpen" :title="$tx('Grading & Return Shipping Pricing')" width="920px" append-to-body>
      <el-divider content-position="left">{{ $tx('Base Grading Service') }}</el-divider>
      <el-form :inline="true" :model="servicePriceForm" class="service-price-form">
        <el-form-item :label="$tx('Service Name')" required><el-input v-model="servicePriceForm.displayName" maxlength="128" /></el-form-item>
        <el-form-item :label="$tx('Currency')" required><el-input v-model="servicePriceForm.currencyCode" maxlength="3" style="width:90px" /></el-form-item>
        <el-form-item :label="$tx('Unit Price')" required><el-input-number v-model="servicePriceForm.unitPrice" :min="0" :precision="2" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="savingServicePrice" @click="saveServicePriceConfig">{{ $tx('Save Grading Price') }}</el-button></el-form-item>
      </el-form>
      <el-alert :closable="false" type="info" show-icon :title="$tx('Current pricing version v{version}. New prices affect new orders only; historical order snapshots remain unchanged.', { version: servicePriceForm.versionNo || 1 })" />
      <el-divider content-position="left">{{ $tx('Return Shipping Options') }}</el-divider>
      <el-table :data="shippingConfigRows" size="small" border><el-table-column :label="$tx('Code')" prop="optionCode" min-width="150" /><el-table-column :label="$tx('Name')" prop="displayName" min-width="170" /><el-table-column :label="$tx('Countries')" prop="countryScope" min-width="150" /><el-table-column :label="$tx('Price')" width="120"><template #default="scope">{{ scope.row.currencyCode }} {{ Number(scope.row.priceAmount).toFixed(2) }}</template></el-table-column><el-table-column :label="$tx('Status')" width="90"><template #default="scope"><el-tag :type="scope.row.active ? 'success' : 'info'">{{ scope.row.active ? $tx('Active') : $tx('Inactive') }}</el-tag></template></el-table-column><el-table-column :label="$tx('Actions')" width="90"><template #default="scope"><el-button link type="primary" @click="editShippingOption(scope.row)">{{ $tx('Edit') }}</el-button></template></el-table-column></el-table>
      <el-divider content-position="left">{{ shippingConfigForm.id ? $tx('Edit Option') : $tx('New Option') }}</el-divider>
      <el-form :model="shippingConfigForm" label-width="110px"><el-row :gutter="12"><el-col :span="8"><el-form-item :label="$tx('Option Code')" required><el-input v-model="shippingConfigForm.optionCode" :disabled="Boolean(shippingConfigForm.id)" /></el-form-item></el-col><el-col :span="8"><el-form-item :label="$tx('Display Name')" required><el-input v-model="shippingConfigForm.displayName" /></el-form-item></el-col><el-col :span="8"><el-form-item :label="$tx('Order')"><el-input-number v-model="shippingConfigForm.sortOrder" :min="0" /></el-form-item></el-col><el-col :span="12"><el-form-item :label="$tx('Countries')" required><el-input v-model="shippingConfigForm.countryScope" :placeholder="$tx('* or US,CA,CN')" /></el-form-item></el-col><el-col :span="6"><el-form-item :label="$tx('Currency')" required><el-input v-model="shippingConfigForm.currencyCode" maxlength="3" /></el-form-item></el-col><el-col :span="6"><el-form-item :label="$tx('Price')" required><el-input-number v-model="shippingConfigForm.priceAmount" :min="0" :precision="2" /></el-form-item></el-col><el-col :span="24"><el-form-item :label="$tx('Description')"><el-input v-model="shippingConfigForm.description" maxlength="512" /></el-form-item></el-col><el-col :span="8"><el-form-item :label="$tx('Active')"><el-switch v-model="shippingConfigForm.active" /></el-form-item></el-col></el-row></el-form>
      <template #footer><el-button @click="resetShippingConfigForm">{{ $tx('Clear') }}</el-button><el-button type="primary" :loading="savingShippingConfig" @click="saveShippingConfig">{{ $tx('Save Option') }}</el-button></template>
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
  { value: 'preprocess', label: tx('Card Preprocessing') }, { value: 'vision', label: tx('Machine Vision Inspection') },
  { value: 'manual_review', label: tx('Manual Review') }, { value: 'encapsulation', label: tx('Standard Encapsulation') }
]
const trackingEventOptions = [
  { value: 'label_created', label: tx('Label Created') }, { value: 'picked_up', label: tx('Picked Up') },
  { value: 'in_transit', label: tx('In Transit') }, { value: 'customs', label: tx('Customs') },
  { value: 'exception', label: tx('Shipping Exception') }, { value: 'out_for_delivery', label: tx('Out for Delivery') },
  { value: 'delivered', label: tx('Delivered') }
]

const statusOptions = [
  { value: 'awaiting_payment', label: tx('Awaiting Payment') }, { value: 'payment_review', label: tx('Payment Review') }, { value: 'awaiting_inbound', label: tx('Awaiting Cards') }, { value: 'inbound_shipped', label: tx('Shipped to NXR') }, { value: 'intake_exception', label: tx('Intake Exception') }, { value: 'received', label: tx('Cards Received') }, { value: 'grading', label: tx('Grading') }, { value: 'review', label: tx('Review') }, { value: 'quality_check', label: tx('Quality Check') }, { value: 'quality_hold', label: tx('QC Rework') }, { value: 'completed', label: tx('Ready to Return') }, { value: 'return_shipped', label: tx('Return Shipped') }, { value: 'delivered', label: tx('Delivered') }, { value: 'cancelled', label: tx('Cancelled') }
]

const extraStatusLabels = {
  shortage: tx('Shortage'), overage: tx('Overage'), damaged: tx('Damaged'), missing_barcode: tx('Missing Barcode'), other: tx('Other'),
  open: tx('Open'), assigned: tx('Assigned'), waiting_customer: tx('Waiting for Customer'), resolved: tx('Resolved'), closed: tx('Closed'),
  shipping_change: tx('Shipping Change'), score_dispute: tx('Grade Dispute'), inquiry: tx('Inquiry'), requested: tx('Pending Review'),
  awaiting_settlement: tx('Awaiting Settlement'), settled: tx('Settled'), rejected: tx('Rejected'), pending: tx('Pending'),
  in_progress: tx('In Progress'), failed: tx('Failed'), completed: tx('Completed')
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
  if (paymentAction.value === 'reject' && !paymentForm.note.trim()) { proxy.$modal.msgWarning(tx('Enter a rejection reason')); return }
  savingPayment.value = true
  const call = paymentAction.value === 'confirm' ? confirmGradingPayment : rejectGradingPayment
  const payload = paymentAction.value === 'confirm' ? { providerTransactionId: paymentForm.providerTransactionId, note: paymentForm.note } : { note: paymentForm.note }
  call(detail.value.id, activePayment.value.id, payload).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; paymentDialogOpen.value = false; proxy.$modal.msgSuccess(paymentAction.value === 'confirm' ? tx('Payment confirmed') : tx('Payment rejected')); return loadOrders() }).finally(() => { savingPayment.value = false })
}

function saveStatus() {
  if (!detail.value || !statusForm.statusCode) return
  savingStatus.value = true
  updateGradingOrderStatus(detail.value.id, { statusCode: statusForm.statusCode, detail: '' }).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; proxy.$modal.msgSuccess(tx('Order status updated')); return loadOrders() }).finally(() => { savingStatus.value = false })
}

function saveShipment() {
  if (!detail.value || !shipmentForm.carrierName.trim() || !shipmentForm.trackingNumber.trim()) { proxy.$modal.msgWarning(tx('Enter a carrier and tracking number')); return }
  savingShipment.value = true
  createGradingShipment(detail.value.id, shipmentForm).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; shipmentForm.carrierName = ''; shipmentForm.trackingNumber = ''; shipmentForm.note = ''; proxy.$modal.msgSuccess(tx('Shipment added')); return loadOrders() }).finally(() => { savingShipment.value = false })
}

function markDelivered(shipment) {
  if (!detail.value) return
  proxy.$modal.confirm(tx('Mark this shipment as delivered?')).then(() => markGradingShipmentDelivered(detail.value.id, shipment.id)).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; proxy.$modal.msgSuccess(tx('Shipment marked as delivered')); return loadOrders() }).catch(() => {})
}

function linkSubmission(item) {
  if (!detail.value || !submissionLinks[item.id]) { proxy.$modal.msgWarning(tx('Enter a grading entry ID')); return }
  linkingItemId.value = item.id
  linkGradingOrderItem(detail.value.id, item.id, submissionLinks[item.id]).then((res) => { detail.value = res.data; statusForm.statusCode = detail.value.statusCode; proxy.$modal.msgSuccess(tx('Grading entry linked')) }).finally(() => { linkingItemId.value = null })
}

async function lookupIntakeOrder() {
  if (!intakeLookupCode.value.trim()) { proxy.$modal.msgWarning(tx('Enter or scan an intake code')); return }
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
  if (!detail.value || !intakeForm.intakeCode.trim()) { proxy.$modal.msgWarning(tx('Intake code is required')); return }
  receivingOrder.value = true
  try {
    await receiveGradingOrder(detail.value.id, intakeForm)
    proxy.$modal.msgSuccess(tx('Warehouse intake and card count recorded'))
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
  if (!detail.value || !activeException.value || !exceptionResolutionForm.resolutionNote.trim()) { proxy.$modal.msgWarning(tx('Enter the resolution')); return }
  resolvingException.value = true
  try {
    const response = await resolveOrderException(detail.value.id, activeException.value.id, exceptionResolutionForm)
    operations.value = response.data
    exceptionDialogOpen.value = false
    proxy.$modal.msgSuccess(tx('Exception resolved'))
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
    proxy.$modal.msgSuccess(tx('Task created'))
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
    proxy.$modal.msgSuccess(tx('Task progress saved'))
    await refreshDetail()
  } finally {
    savingTaskId.value = null
  }
}

async function saveQualityCheck() {
  if (!detail.value) return
  if (!qualityForm.passed && !qualityForm.note.trim()) { proxy.$modal.msgWarning(tx('Explain the issue when requesting rework')); return }
  savingQuality.value = true
  try {
    await runOrderQualityCheck(detail.value.id, qualityForm)
    proxy.$modal.msgSuccess(qualityForm.passed ? tx('Quality check passed; order is ready for return shipping') : tx('Order moved to rework'))
    qualityForm.note = ''
    await refreshDetail()
  } finally {
    savingQuality.value = false
  }
}

async function saveTrackingEvent() {
  if (!detail.value || !trackingForm.shipmentId) { proxy.$modal.msgWarning(tx('Select a shipment')); return }
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
    proxy.$modal.msgSuccess(tx('Tracking updated'))
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
    proxy.$modal.msgSuccess(tx('Ticket updated'))
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
  if (!detail.value || !activeShippingChange.value || !shippingActionForm.note.trim()) { proxy.$modal.msgWarning(tx('Enter a processing note')); return }
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
    proxy.$modal.msgSuccess(tx('Return shipping change processed'))
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
  if (!shippingConfigForm.optionCode.trim() || !shippingConfigForm.displayName.trim()) { proxy.$modal.msgWarning(tx('Enter an option code and name')); return }
  savingShippingConfig.value = true
  try {
    await saveReturnShippingOption(shippingConfigForm.id, shippingConfigForm)
    proxy.$modal.msgSuccess(tx('Return shipping option saved; historical order snapshots are unchanged'))
    resetShippingConfigForm()
    await loadShippingConfig()
  } finally {
    savingShippingConfig.value = false
  }
}

async function saveServicePriceConfig() {
  if (!servicePriceForm.displayName.trim() || !servicePriceForm.currencyCode.trim()) { proxy.$modal.msgWarning(tx('Enter a service name and currency')); return }
  savingServicePrice.value = true
  try {
    const response = await saveGradingServicePrice(servicePriceForm)
    Object.assign(servicePriceForm, response.data)
    proxy.$modal.msgSuccess(tx('Grading price saved; historical order snapshots are unchanged'))
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
