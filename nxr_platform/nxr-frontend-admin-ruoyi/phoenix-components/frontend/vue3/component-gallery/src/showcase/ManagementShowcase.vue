<script setup lang="ts">
import {
  PhoenixAuditLogPage,
  PhoenixBookingManagementPage,
  PhoenixDepartmentManagementPage,
  PhoenixDictionaryConfigurationPage,
  PhoenixInventoryManagementPage,
  PhoenixOrderManagementPage,
  PhoenixProductManagementPage,
  PhoenixRolePermissionPage,
  PhoenixUserManagementPage,
} from '../patterns/management'

const stats = [
  { label: '全部', value: 1286, tone: 'primary' as const },
  { label: '今日新增', value: 24, tone: 'success' as const },
  { label: '待处理', value: 8, tone: 'warning' as const },
]
</script>

<template>
  <div class="cg-management-showcase">
    <article><h3>用户管理页</h3><PhoenixUserManagementPage :stats="stats"><template #filters>姓名、部门、状态查询</template><div class="cg-management-placeholder">用户数据表格</div><template #detail>用户资料与登录会话</template></PhoenixUserManagementPage></article>
    <article><h3>角色权限管理页</h3><PhoenixRolePermissionPage><template #roles>管理员<br>运营人员<br>审核人员</template><template #permissions><div class="cg-management-placeholder">角色权限矩阵</div></template></PhoenixRolePermissionPage></article>
    <article><h3>部门管理页</h3><PhoenixDepartmentManagementPage><template #tree>总部<br>产品中心<br>技术中心</template><template #members><div class="cg-management-placeholder">部门成员列表</div></template></PhoenixDepartmentManagementPage></article>
    <article><h3>字典配置页</h3><PhoenixDictionaryConfigurationPage><template #categories>业务状态<br>资源类型<br>区域编码</template><template #entries><div class="cg-management-placeholder">字典条目</div></template></PhoenixDictionaryConfigurationPage></article>
    <article><h3>审计日志页</h3><PhoenixAuditLogPage :stats="stats"><template #filters>操作人、时间、资源筛选</template><div class="cg-management-placeholder">审计日志列表</div><template #detail>操作前后数据</template></PhoenixAuditLogPage></article>
    <article><h3>商品管理页</h3><PhoenixProductManagementPage :stats="stats"><template #filters>商品名称、分类、上下架状态</template><div class="cg-management-placeholder">商品与 SKU 列表</div><template #editor>商品编辑面板</template></PhoenixProductManagementPage></article>
    <article><h3>订单管理页</h3><PhoenixOrderManagementPage :stats="stats"><template #filters>订单号、用户、支付状态</template><div class="cg-management-placeholder">订单数据列表</div><template #detail>订单明细与履约</template></PhoenixOrderManagementPage></article>
    <article><h3>库存管理页</h3><PhoenixInventoryManagementPage :stats="stats"><template #filters>仓库、SKU、库存状态</template><div class="cg-management-placeholder">库存批次列表</div><template #alerts>低库存预警</template></PhoenixInventoryManagementPage></article>
    <article><h3>预约管理页</h3><PhoenixBookingManagementPage :stats="stats"><template #filters>日期、资源、预约状态</template><div class="cg-management-placeholder">预约记录列表</div><template #calendar>预约日历</template></PhoenixBookingManagementPage></article>
  </div>
</template>
