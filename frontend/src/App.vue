<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CirclePlus,
  CloseBold,
  Delete,
  DocumentChecked,
  Goods,
  Lightning,
  Money,
  Refresh,
  Search,
  Select,
  ShoppingBag,
  ShoppingCart,
  SwitchButton,
  Tickets,
  User
} from '@element-plus/icons-vue'
import {
  LATEST_ORDER_SN_KEY,
  LATEST_REQUEST_ID_KEY,
  TOKEN_KEY,
  USER_ID_KEY,
  USERNAME_KEY
} from './api/http'
import {
  addCartItem,
  cancelOrder,
  confirmOrder,
  createOrder,
  getCart,
  getOrder,
  getProduct,
  getSeckillActivities,
  getSeckillResult,
  login,
  payOrder,
  removeCartItem,
  submitSeckill,
  updateCartItem
} from './api/mall'

const loginForm = reactive({
  username: localStorage.getItem(USERNAME_KEY) || 'alice',
  password: 'demo123'
})

const session = reactive({
  token: localStorage.getItem(TOKEN_KEY) || '',
  username: localStorage.getItem(USERNAME_KEY) || '',
  userId: localStorage.getItem(USER_ID_KEY) || ''
})

const loading = reactive({
  login: false,
  product: false,
  addCart: false,
  cart: false,
  order: false,
  seckill: false
})

const skuId = ref(1001)
const quantity = ref(1)
const product = ref(null)
const cartItems = ref([])
const confirmData = ref(null)
const remark = ref('frontend demo order')
const orderSn = ref(localStorage.getItem(LATEST_ORDER_SN_KEY) || '')
const currentOrder = ref(null)
const activities = ref([])
const requestId = ref(localStorage.getItem(LATEST_REQUEST_ID_KEY) || '')
const seckillResult = ref(null)

const isLoggedIn = computed(() => Boolean(session.token))
const cartTotal = computed(() =>
  cartItems.value.reduce((sum, item) => sum + Number(item.price || 0) * Number(item.quantity || 0), 0)
)
const checkedCount = computed(() => cartItems.value.filter((item) => item.checked).length)

function saveSession(data) {
  session.token = data.token
  session.username = data.username
  session.userId = String(data.userId)
  localStorage.setItem(TOKEN_KEY, data.token)
  localStorage.setItem(USERNAME_KEY, data.username)
  localStorage.setItem(USER_ID_KEY, String(data.userId))
}

function clearSession() {
  session.token = ''
  session.username = ''
  session.userId = ''
  cartItems.value = []
  confirmData.value = null
  currentOrder.value = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USERNAME_KEY)
  localStorage.removeItem(USER_ID_KEY)
}

function requireLogin() {
  if (isLoggedIn.value) {
    return true
  }
  ElMessage.warning('请先登录')
  return false
}

async function handleLogin() {
  loading.login = true
  try {
    const data = await login({ ...loginForm })
    saveSession(data)
    ElMessage.success('登录成功')
    await refreshCart()
  } finally {
    loading.login = false
  }
}

function logout() {
  clearSession()
  ElMessage.success('已退出登录')
}

async function loadProduct() {
  loading.product = true
  try {
    product.value = await getProduct(skuId.value)
  } finally {
    loading.product = false
  }
}

async function addProductToCart() {
  if (!requireLogin()) {
    return
  }
  if (!product.value || Number(product.value.skuId) !== Number(skuId.value)) {
    await loadProduct()
  }
  loading.addCart = true
  try {
    await addCartItem({
      skuId: product.value.skuId,
      skuName: product.value.skuName,
      price: product.value.price,
      quantity: quantity.value
    })
    ElMessage.success('已加入购物车')
    await refreshCart()
  } finally {
    loading.addCart = false
  }
}

async function refreshCart() {
  if (!isLoggedIn.value) {
    return
  }
  loading.cart = true
  try {
    cartItems.value = await getCart()
  } finally {
    loading.cart = false
  }
}

async function saveCartItem(item) {
  if (!requireLogin()) {
    return
  }
  await updateCartItem(item.skuId, {
    quantity: item.quantity,
    checked: item.checked
  })
  ElMessage.success('购物车已更新')
  await refreshCart()
}

async function deleteCartItem(item) {
  await removeCartItem(item.skuId)
  ElMessage.success('已移除商品')
  await refreshCart()
}

async function handleConfirmOrder() {
  if (!requireLogin()) {
    return
  }
  loading.order = true
  try {
    confirmData.value = await confirmOrder()
  } finally {
    loading.order = false
  }
}

async function handleCreateOrder() {
  if (!requireLogin()) {
    return
  }
  loading.order = true
  try {
    const order = await createOrder({ remark: remark.value })
    setCurrentOrder(order)
    ElMessage.success('订单已创建')
    await refreshCart()
  } finally {
    loading.order = false
  }
}

function setCurrentOrder(order) {
  currentOrder.value = order
  if (order?.orderSn) {
    orderSn.value = order.orderSn
    localStorage.setItem(LATEST_ORDER_SN_KEY, order.orderSn)
  }
}

async function handleGetOrder() {
  if (!requireLogin() || !orderSn.value) {
    return
  }
  loading.order = true
  try {
    setCurrentOrder(await getOrder(orderSn.value))
  } finally {
    loading.order = false
  }
}

async function handlePayOrder() {
  if (!requireLogin() || !orderSn.value) {
    return
  }
  loading.order = true
  try {
    setCurrentOrder(await payOrder(orderSn.value))
    ElMessage.success('订单已支付')
  } finally {
    loading.order = false
  }
}

async function handleCancelOrder() {
  if (!requireLogin() || !orderSn.value) {
    return
  }
  loading.order = true
  try {
    setCurrentOrder(await cancelOrder(orderSn.value))
    ElMessage.success('订单已取消')
  } finally {
    loading.order = false
  }
}

async function loadSeckillActivities() {
  loading.seckill = true
  try {
    activities.value = await getSeckillActivities()
  } finally {
    loading.seckill = false
  }
}

async function handleSubmitSeckill(activityId, seckillSkuId) {
  if (!requireLogin()) {
    return
  }
  loading.seckill = true
  try {
    const data = await submitSeckill(activityId, seckillSkuId)
    requestId.value = data.requestId
    localStorage.setItem(LATEST_REQUEST_ID_KEY, data.requestId)
    seckillResult.value = {
      requestId: data.requestId,
      status: data.status,
      message: data.message
    }
    ElMessage.success(data.message || '秒杀请求已提交')
  } finally {
    loading.seckill = false
  }
}

async function handleQuerySeckillResult() {
  if (!requireLogin() || !requestId.value) {
    return
  }
  loading.seckill = true
  try {
    seckillResult.value = await getSeckillResult(requestId.value)
    if (seckillResult.value?.orderSn) {
      orderSn.value = seckillResult.value.orderSn
      localStorage.setItem(LATEST_ORDER_SN_KEY, seckillResult.value.orderSn)
    }
  } finally {
    loading.seckill = false
  }
}

function formatMoney(value) {
  return `￥${Number(value || 0).toFixed(2)}`
}

function formatTime(value) {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function orderStatusType(status) {
  const typeMap = {
    CREATED: 'warning',
    PAID: 'success',
    CANCELED: 'info',
    CLOSED: 'danger'
  }
  return typeMap[status] || 'info'
}

onMounted(async () => {
  await Promise.allSettled([loadProduct(), loadSeckillActivities()])
  if (isLoggedIn.value) {
    await refreshCart()
  }
})
</script>

<template>
  <main class="app-shell">
    <header class="topbar">
      <div class="topbar-inner">
        <div class="brand">
          <div class="brand-mark">
            <el-icon :size="22"><ShoppingBag /></el-icon>
          </div>
          <div>
            <h1 class="brand-title">Mall Demo Console</h1>
            <p class="brand-subtitle">Nginx 8080 -> Gateway 8100 -> Mall Services</p>
          </div>
        </div>
        <div class="session">
          <el-tag v-if="isLoggedIn" type="success" effect="plain">
            {{ session.username }} / {{ session.userId }}
          </el-tag>
          <el-tag v-else type="info" effect="plain">未登录</el-tag>
          <el-button v-if="isLoggedIn" :icon="SwitchButton" plain @click="logout">退出</el-button>
        </div>
      </div>
    </header>

    <section class="content">
      <div class="grid">
        <div class="stack">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><User /></el-icon>
                  登录
                </h2>
                <el-tag type="info" effect="plain">POST /api/auth/login</el-tag>
              </div>
            </template>

            <el-form :model="loginForm" label-position="top" @submit.prevent="handleLogin">
              <div class="form-row">
                <el-form-item label="用户名">
                  <el-input v-model="loginForm.username" autocomplete="username" />
                </el-form-item>
                <el-form-item label="密码">
                  <el-input v-model="loginForm.password" type="password" autocomplete="current-password" show-password />
                </el-form-item>
                <el-form-item label="操作">
                  <el-button type="primary" :icon="Select" :loading="loading.login" @click="handleLogin">
                    登录并保存 Token
                  </el-button>
                </el-form-item>
              </div>
            </el-form>

            <div v-if="session.token" class="code-line">
              {{ session.token }}
            </div>
          </el-card>

          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><Goods /></el-icon>
                  商品
                </h2>
                <div class="toolbar">
                  <el-tag effect="plain">默认 SKU 1001</el-tag>
                  <el-button :icon="Refresh" plain :loading="loading.product" @click="loadProduct">刷新</el-button>
                </div>
              </div>
            </template>

            <div class="query-row">
              <el-input-number v-model="skuId" :min="1" controls-position="right" />
              <el-button type="primary" :icon="Search" :loading="loading.product" @click="loadProduct">
                查询商品
              </el-button>
              <el-button type="success" :icon="ShoppingCart" :loading="loading.addCart" @click="addProductToCart">
                加入购物车
              </el-button>
            </div>

            <template v-if="product">
              <div style="margin-top: 18px">
                <p class="product-name">{{ product.skuName }}</p>
                <p class="muted">{{ product.spuName }} / {{ product.brandName }} / {{ product.categoryName }}</p>
              </div>

              <div class="metric-grid">
                <div class="metric">
                  <div class="metric-label">SKU</div>
                  <div class="metric-value">{{ product.skuId }}</div>
                </div>
                <div class="metric">
                  <div class="metric-label">价格</div>
                  <div class="metric-value">{{ formatMoney(product.price) }}</div>
                </div>
                <div class="metric">
                  <div class="metric-label">库存</div>
                  <div class="metric-value">{{ product.stock }}</div>
                </div>
                <div class="metric">
                  <div class="metric-label">数量</div>
                  <el-input-number v-model="quantity" :min="1" :max="99" size="small" />
                </div>
              </div>

              <div v-if="product.skuOptions?.length" class="table-wrap" style="margin-top: 14px">
                <el-table :data="product.skuOptions" border size="small">
                  <el-table-column prop="skuId" label="SKU" width="110" />
                  <el-table-column prop="skuName" label="名称" min-width="180" />
                  <el-table-column label="价格" width="110">
                    <template #default="{ row }">{{ formatMoney(row.price) }}</template>
                  </el-table-column>
                  <el-table-column label="操作" width="90">
                    <template #default="{ row }">
                      <el-button text type="primary" @click="skuId = row.skuId; loadProduct()">选择</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </template>

            <div v-else class="empty-box" style="margin-top: 14px">暂无商品数据</div>
          </el-card>

          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><Tickets /></el-icon>
                  订单
                </h2>
                <div class="toolbar">
                  <el-button :icon="DocumentChecked" plain :loading="loading.order" @click="handleConfirmOrder">
                    确认单
                  </el-button>
                  <el-button type="primary" :icon="CirclePlus" :loading="loading.order" @click="handleCreateOrder">
                    创建订单
                  </el-button>
                </div>
              </div>
            </template>

            <el-input v-model="remark" placeholder="订单备注" clearable />

            <div v-if="confirmData" class="order-block" style="margin-top: 14px">
              <div class="panel-header">
                <strong>确认单</strong>
                <el-tag type="success" effect="plain">{{ formatMoney(confirmData.totalAmount) }}</el-tag>
              </div>
              <div class="table-wrap" style="margin-top: 10px">
                <el-table :data="confirmData.items" border size="small">
                  <el-table-column prop="skuId" label="SKU" width="100" />
                  <el-table-column prop="skuName" label="商品" min-width="170" />
                  <el-table-column prop="quantity" label="数量" width="80" />
                  <el-table-column label="金额" width="110">
                    <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
                  </el-table-column>
                </el-table>
              </div>
            </div>

            <div class="query-row" style="margin-top: 14px">
              <el-input v-model="orderSn" placeholder="订单号" clearable />
              <el-button :icon="Search" :loading="loading.order" @click="handleGetOrder">查询</el-button>
              <el-button type="success" :icon="Money" :loading="loading.order" @click="handlePayOrder">支付</el-button>
            </div>

            <div class="order-actions">
              <el-button type="danger" plain :icon="CloseBold" :loading="loading.order" @click="handleCancelOrder">
                取消订单
              </el-button>
            </div>

            <div v-if="currentOrder" class="order-block" style="margin-top: 14px">
              <div class="panel-header">
                <strong>{{ currentOrder.orderSn }}</strong>
                <el-tag :type="orderStatusType(currentOrder.status)" effect="light">{{ currentOrder.status }}</el-tag>
              </div>
              <div class="status-strip">
                <el-tag effect="plain">{{ currentOrder.source }}</el-tag>
                <el-tag effect="plain">{{ formatMoney(currentOrder.totalAmount) }}</el-tag>
                <el-tag effect="plain">{{ formatTime(currentOrder.createdAt) }}</el-tag>
              </div>
            </div>
          </el-card>
        </div>

        <aside class="stack">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><ShoppingCart /></el-icon>
                  购物车
                </h2>
                <el-button :icon="Refresh" plain :loading="loading.cart" @click="refreshCart">刷新</el-button>
              </div>
            </template>

            <div class="status-strip" style="margin-top: 0; margin-bottom: 12px">
              <el-tag type="success" effect="plain">{{ checkedCount }} 件已选</el-tag>
              <el-tag type="warning" effect="plain">{{ formatMoney(cartTotal) }}</el-tag>
            </div>

            <div v-if="cartItems.length" class="cart-list">
              <div v-for="item in cartItems" :key="item.skuId" class="cart-item">
                <div class="cart-item-main">
                  <div>
                    <p class="item-title">{{ item.skuName }}</p>
                    <div class="item-meta">SKU {{ item.skuId }} / {{ formatMoney(item.price) }}</div>
                  </div>
                  <el-switch v-model="item.checked" @change="saveCartItem(item)" />
                </div>
                <div class="cart-actions">
                  <el-input-number v-model="item.quantity" :min="1" :max="99" size="small" @change="saveCartItem(item)" />
                  <el-button type="danger" plain :icon="Delete" @click="deleteCartItem(item)">删除</el-button>
                </div>
              </div>
            </div>

            <div v-else class="empty-box">购物车为空</div>
          </el-card>

          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><Lightning /></el-icon>
                  秒杀
                </h2>
                <el-button :icon="Refresh" plain :loading="loading.seckill" @click="loadSeckillActivities">刷新</el-button>
              </div>
            </template>

            <div v-if="activities.length" class="activity-list">
              <div v-for="activity in activities" :key="activity.activityId" class="activity-item">
                <div class="activity-main">
                  <div>
                    <p class="item-title">{{ activity.activityName }}</p>
                    <div class="item-meta">
                      {{ formatTime(activity.startAt) }} - {{ formatTime(activity.endAt) }}
                    </div>
                  </div>
                  <el-tag type="danger" effect="plain">活动 {{ activity.activityId }}</el-tag>
                </div>

                <table class="sku-table">
                  <thead>
                    <tr>
                      <th>SKU</th>
                      <th>商品</th>
                      <th>秒杀价</th>
                      <th>库存</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="sku in activity.skus" :key="`${activity.activityId}-${sku.skuId}`">
                      <td>{{ sku.skuId }}</td>
                      <td>{{ sku.skuName }}</td>
                      <td>{{ formatMoney(sku.price) }}</td>
                      <td>{{ sku.stock }}</td>
                      <td>
                        <el-button
                          size="small"
                          type="danger"
                          :icon="Lightning"
                          :loading="loading.seckill"
                          @click="handleSubmitSeckill(activity.activityId, sku.skuId)"
                        >
                          提交
                        </el-button>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            <div v-else class="empty-box">暂无秒杀活动</div>

            <div class="query-row" style="margin-top: 14px">
              <el-input v-model="requestId" placeholder="requestId" clearable />
              <el-button :icon="Search" :loading="loading.seckill" @click="handleQuerySeckillResult">查结果</el-button>
              <el-button v-if="seckillResult?.orderSn" type="primary" plain @click="orderSn = seckillResult.orderSn; handleGetOrder()">
                查订单
              </el-button>
            </div>

            <div v-if="seckillResult" class="order-block" style="margin-top: 14px">
              <div class="panel-header">
                <strong>{{ seckillResult.status }}</strong>
                <el-tag effect="plain">{{ seckillResult.requestId }}</el-tag>
              </div>
              <p class="muted">{{ seckillResult.message || '暂无消息' }}</p>
              <div v-if="seckillResult.orderSn" class="code-line">{{ seckillResult.orderSn }}</div>
            </div>
          </el-card>
        </aside>
      </div>
    </section>
  </main>
</template>
