<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  ChatDotRound,
  CirclePlus,
  CloseBold,
  Connection,
  Delete,
  DocumentChecked,
  Goods,
  Lightning,
  Money,
  Picture,
  Plus,
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
  getCategoryTree,
  getOrder,
  getProduct,
  getSeckillActivities,
  getSeckillResult,
  login,
  payOrder,
  removeCartItem,
  searchProducts,
  submitSeckill,
  updateCartItem
} from './api/mall'

const AGENT_STATE_KEY = 'mall_agent_workbench_state'
const pageTitles = {
  agent: '导购 Agent',
  products: '商品',
  cart: '购物车',
  orders: '订单',
  seckill: '秒杀'
}

const navItems = [
  { key: 'agent', label: '导购', icon: ChatDotRound },
  { key: 'products', label: '商品', icon: Goods },
  { key: 'cart', label: '购物车', icon: ShoppingCart },
  { key: 'orders', label: '订单', icon: Tickets },
  { key: 'seckill', label: '秒杀', icon: Lightning }
]

const promptChips = [
  '预算 300 元以内，帮我选一个通勤耳机',
  '对比一下当前商品里更适合运动的 SKU',
  '检查我的购物车，提醒价格、库存和下单风险',
  '我想买数码礼物，优先考虑口碑和库存'
]

const route = ref(readInitialRoute())
const chatLogRef = ref(null)
const imageInputRef = ref(null)
const selectedImages = ref([])
let activeAbortController = null

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
  search: false,
  addCart: false,
  cart: false,
  order: false,
  seckill: false,
  agent: false,
  agentProducts: false
})

const productQuery = reactive({
  keyword: '',
  categoryId: '',
  brand: '',
  minPrice: null,
  maxPrice: null,
  limit: 12
})

const skuId = ref(1001)
const quantity = ref(1)
const product = ref(null)
const productResults = ref([])
const categoryTree = ref([])
const cartItems = ref([])
const confirmData = ref(null)
const remark = ref('frontend demo order')
const orderSn = ref(localStorage.getItem(LATEST_ORDER_SN_KEY) || '')
const currentOrder = ref(null)
const activities = ref([])
const requestId = ref(localStorage.getItem(LATEST_REQUEST_ID_KEY) || '')
const seckillResult = ref(null)

const agent = reactive(loadAgentState())

const isLoggedIn = computed(() => Boolean(session.token))
const currentTitle = computed(() => pageTitles[route.value] || pageTitles.agent)
const currentPageDescription = computed(() => {
  const descriptions = {
    agent: '参考 RAGAgent demo 的导购工作台，支持聊天、推荐、对比、加购和跨页面跳转。',
    products: '检索 SKU、查看商品详情，并把候选商品送去导购页追问或对比。',
    cart: '查看并维护当前登录用户的购物车，也可以让导购检查购物车风险。',
    orders: '确认、创建、查询、支付和取消普通订单。',
    seckill: '查看秒杀活动，提交秒杀请求并追踪异步结果。'
  }
  return descriptions[route.value] || descriptions.agent
})
const cartTotal = computed(() =>
  cartItems.value.reduce((sum, item) => sum + Number(item.price || 0) * Number(item.quantity || 0), 0)
)
const checkedCount = computed(() => cartItems.value.filter((item) => item.checked).length)
const cartQuantity = computed(() =>
  cartItems.value.reduce((sum, item) => sum + Number(item.quantity || 0), 0)
)
const agentProducts = computed(() => normalizeAgentProducts())
const comparedProducts = computed(() =>
  agent.compareIds
    .map((id) => agentProducts.value.find((item) => item.productId === id))
    .filter(Boolean)
)
const agentCartCount = computed(() => cartQuantity.value)

window.addEventListener('hashchange', () => {
  route.value = readInitialRoute()
})

function readInitialRoute() {
  const key = window.location.hash.replace(/^#\/?/, '')
  return pageTitles[key] ? key : 'agent'
}

function navigate(page) {
  if (!pageTitles[page]) {
    return
  }
  window.location.hash = `#/${page}`
  route.value = page
}

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
  navigate('agent')
  return false
}

async function handleLogin() {
  loading.login = true
  try {
    const data = await login({ ...loginForm })
    saveSession(data)
    ElMessage.success('登录成功')
    await Promise.allSettled([refreshCart(), refreshAgentProducts(false)])
  } finally {
    loading.login = false
  }
}

function logout() {
  clearSession()
  ElMessage.success('已退出登录')
}

async function loadProduct(targetSkuId = skuId.value, jump = false) {
  loading.product = true
  try {
    skuId.value = Number(targetSkuId || skuId.value)
    product.value = await getProduct(skuId.value)
    rememberAgentProduct(productToAgentProduct(product.value))
    if (jump) {
      navigate('products')
    }
  } finally {
    loading.product = false
  }
}

async function searchProductList() {
  loading.search = true
  try {
    const params = cleanParams({
      keyword: productQuery.keyword,
      categoryId: productQuery.categoryId,
      brand: productQuery.brand,
      minPrice: productQuery.minPrice,
      maxPrice: productQuery.maxPrice,
      limit: productQuery.limit
    })
    const items = await searchProducts(params)
    productResults.value = Array.isArray(items) ? items : []
    productResults.value.forEach((item) => rememberAgentProduct(searchItemToAgentProduct(item)))
    if (!productResults.value.length) {
      ElMessage.info('没有匹配的商品')
    }
  } finally {
    loading.search = false
  }
}

async function loadCategoryTree() {
  try {
    categoryTree.value = await getCategoryTree()
  } catch {
    categoryTree.value = []
  }
}

async function addProductToCart(targetProduct = product.value, count = quantity.value) {
  if (!requireLogin()) {
    return
  }
  let resolvedProduct = targetProduct
  if (!resolvedProduct || !resolvedProduct.skuId) {
    await loadProduct()
    resolvedProduct = product.value
  }
  loading.addCart = true
  try {
    await addCartItem({
      skuId: Number(resolvedProduct.skuId),
      skuName: resolvedProduct.skuName || resolvedProduct.title,
      price: resolvedProduct.price,
      quantity: count
    })
    ElMessage.success('已加入购物车')
    await refreshCart()
  } finally {
    loading.addCart = false
  }
}

async function addSearchItemToCart(item) {
  await addProductToCart(
    {
      skuId: item.skuId,
      skuName: item.skuName,
      price: item.price
    },
    1
  )
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

async function handleConfirmOrder(jump = false) {
  if (!requireLogin()) {
    return
  }
  loading.order = true
  try {
    confirmData.value = await confirmOrder()
    if (jump) {
      navigate('orders')
    }
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
    navigate('orders')
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
    navigate('orders')
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

async function handleQuerySeckillResult(jump = false) {
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
    if (jump) {
      navigate('seckill')
    }
  } finally {
    loading.seckill = false
  }
}

function loadAgentState() {
  const fallback = {
    ragBaseUrl: 'http://localhost:8081',
    sessionId: createSessionId(),
    modelId: '',
    modelOptions: [],
    webSearchEnabled: false,
    messageInput: '',
    imageUrl: '',
    messages: [],
    products: [],
    compareIds: [],
    status: '可连接 RAGAgent，也可只使用商城数据做导购演示。'
  }
  try {
    const saved = JSON.parse(localStorage.getItem(AGENT_STATE_KEY) || 'null')
    if (!saved) {
      return fallback
    }
    return {
      ...fallback,
      ...saved,
      modelOptions: Array.isArray(saved.modelOptions) ? saved.modelOptions : [],
      messages: Array.isArray(saved.messages) ? saved.messages : [],
      products: Array.isArray(saved.products) ? saved.products : [],
      compareIds: Array.isArray(saved.compareIds) ? saved.compareIds : []
    }
  } catch {
    return fallback
  }
}

function persistAgentState() {
  localStorage.setItem(
    AGENT_STATE_KEY,
    JSON.stringify({
      ragBaseUrl: agent.ragBaseUrl,
      sessionId: agent.sessionId,
      modelId: agent.modelId,
      modelOptions: agent.modelOptions,
      webSearchEnabled: agent.webSearchEnabled,
      messages: agent.messages,
      products: agent.products,
      compareIds: agent.compareIds,
      status: agent.status
    })
  )
}

async function loadAgentModels(showMessage = false) {
  const baseUrl = normalizeBaseUrl(agent.ragBaseUrl)
  agent.ragBaseUrl = baseUrl
  try {
    const response = await fetch(`${baseUrl}/api/models/chat`, {
      headers: agentHeaders(false)
    })
    if (!response.ok) {
      throw await createFetchError(response)
    }
    const result = await response.json()
    agent.modelOptions = Array.isArray(result.items) ? result.items : []
    if (!agent.modelOptions.some((item) => item.id === agent.modelId)) {
      agent.modelId = result.defaultModel || agent.modelOptions[0]?.id || ''
    }
    agent.status = `RAGAgent 模型列表已加载，共 ${agent.modelOptions.length} 个。`
    persistAgentState()
    if (showMessage) {
      ElMessage.success('导购模型已刷新')
    }
  } catch (error) {
    agent.status = humanizeFetchError(error)
    persistAgentState()
    if (showMessage) {
      ElMessage.warning(agent.status)
    }
  }
}

async function refreshAgentProducts(showMessage = true) {
  loading.agentProducts = true
  try {
    if (agent.ragBaseUrl) {
      const baseUrl = normalizeBaseUrl(agent.ragBaseUrl)
      const endpoint = new URL('/api/shopping/cart/products', `${baseUrl}/`)
      endpoint.searchParams.set('sessionId', agent.sessionId)
      endpoint.searchParams.set('limit', '12')
      const response = await fetch(endpoint, {
        headers: agentHeaders()
      })
      if (response.ok) {
        const result = await response.json()
        if (!result?.degraded && Array.isArray(result?.items)) {
          result.items.forEach((item) => rememberAgentProduct(item))
          agent.status = `已从 RAGAgent 拉取 ${result.items.length} 个导购候选。`
          persistAgentState()
          if (showMessage) {
            ElMessage.success('导购候选已刷新')
          }
          return
        }
      }
    }
    const items = await searchProducts({ limit: 12 })
    productResults.value = Array.isArray(items) ? items : []
    productResults.value.forEach((item) => rememberAgentProduct(searchItemToAgentProduct(item)))
    agent.status = `已使用商城商品搜索刷新 ${productResults.value.length} 个候选。`
    persistAgentState()
  } catch (error) {
    agent.status = humanizeFetchError(error)
    if (showMessage) {
      ElMessage.warning(agent.status)
    }
  } finally {
    loading.agentProducts = false
  }
}

async function sendAgentMessage() {
  if (loading.agent) {
    return
  }
  const text = agent.messageInput.trim()
  const imageUrl = agent.imageUrl.trim()
  const images = [...selectedImages.value]
  if (!text && !imageUrl && !images.length) {
    ElMessage.warning('请输入导购需求或上传商品图片')
    return
  }

  const displayText = text || '请基于图片帮我推荐相似商品'
  const mediaUrls = await createDisplayMediaUrls(images, imageUrl)
  const userMessage = createAgentMessage('user', displayText, false, mediaUrls)
  const assistantMessage = createAgentMessage('assistant', '', true, [])
  agent.messages.push(userMessage, assistantMessage)
  agent.messageInput = ''
  agent.imageUrl = ''
  selectedImages.value = []
  persistAgentState()
  scrollChatToBottom()

  loading.agent = true
  activeAbortController = new AbortController()
  agent.status = '正在调用 RAGAgent 导购接口。'
  try {
    await streamShoppingChat(displayText, images, imageUrl, (chunk) => {
      assistantMessage.content += chunk
      scrollChatToBottom()
    }, activeAbortController.signal)
    assistantMessage.pending = false
    applyAssistantProductHints(assistantMessage.content)
    await Promise.allSettled([refreshCart(), refreshAgentProducts(false)])
    agent.status = '导购响应完成。'
    persistAgentState()
  } catch (error) {
    assistantMessage.pending = false
    assistantMessage.content = assistantMessage.content || humanizeFetchError(error)
    agent.status = assistantMessage.content
    persistAgentState()
  } finally {
    activeAbortController = null
    loading.agent = false
  }
}

async function streamShoppingChat(message, images, imageUrl, onChunk, signal) {
  const formData = new FormData()
  formData.set('message', message)
  formData.set('sessionId', agent.sessionId)
  formData.set('webSearchEnabled', String(Boolean(agent.webSearchEnabled)))
  if (agent.modelId) {
    formData.set('modelId', agent.modelId)
  }
  images.forEach((file) => formData.append('image', file, file.name))
  if (imageUrl) {
    formData.append('imageUrl', imageUrl)
  }

  const response = await fetch(`${normalizeBaseUrl(agent.ragBaseUrl)}/api/shopping/chat`, {
    method: 'POST',
    headers: agentHeaders(),
    body: formData,
    signal
  })
  if (!response.ok) {
    throw await createFetchError(response)
  }
  if (!response.body) {
    const text = await response.text()
    onChunk(text)
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      const tail = decoder.decode()
      if (tail) {
        onChunk(tail)
      }
      break
    }
    onChunk(decoder.decode(value, { stream: true }))
  }
}

function stopAgentMessage() {
  activeAbortController?.abort()
}

function clearAgentMessages() {
  agent.messages = []
  persistAgentState()
}

function resetAgentSession() {
  agent.sessionId = createSessionId()
  agent.messages = []
  agent.status = `已创建新会话：${agent.sessionId}`
  persistAgentState()
}

function toggleWebSearch() {
  agent.webSearchEnabled = !agent.webSearchEnabled
  agent.status = agent.webSearchEnabled ? '已开启联网搜索。' : '已关闭联网搜索。'
  persistAgentState()
}

function askPrompt(prompt) {
  agent.messageInput = prompt
  nextTick(() => scrollChatToBottom())
}

function askAboutProduct(item) {
  rememberAgentProduct(searchItemToAgentProduct(item))
  agent.messageInput = `请详细说明 SKU ${item.skuId} ${item.skuName} 的适用人群、优缺点和替代款。`
  navigate('agent')
}

function askAboutCart() {
  agent.messageInput = '查看我的购物车，帮我检查价格、库存、搭配和下单风险，并给出下一步建议。'
  navigate('agent')
}

function compareProduct(item) {
  const product = item.productId ? item : searchItemToAgentProduct(item)
  rememberAgentProduct(product)
  if (agent.compareIds.includes(product.productId)) {
    agent.compareIds = agent.compareIds.filter((id) => id !== product.productId)
  } else {
    agent.compareIds = [...agent.compareIds, product.productId].slice(-3)
  }
  persistAgentState()
}

function clearCompare() {
  agent.compareIds = []
  persistAgentState()
}

function rememberAgentProduct(product) {
  if (!product?.productId) {
    return
  }
  const existing = agent.products.findIndex((item) => item.productId === product.productId)
  if (existing >= 0) {
    agent.products.splice(existing, 1, { ...agent.products[existing], ...product })
  } else {
    agent.products.unshift(product)
  }
  agent.products = agent.products.slice(0, 24)
  persistAgentState()
}

function normalizeAgentProducts() {
  const fromSearch = productResults.value.map(searchItemToAgentProduct)
  const fromProduct = product.value ? [productToAgentProduct(product.value)] : []
  const merged = [...agent.products, ...fromProduct, ...fromSearch].filter(Boolean)
  const seen = new Set()
  return merged.filter((item) => {
    if (!item.productId || seen.has(item.productId)) {
      return false
    }
    seen.add(item.productId)
    return true
  }).slice(0, 12)
}

function searchItemToAgentProduct(item) {
  if (!item) {
    return null
  }
  return {
    productId: String(item.spuId || item.skuId || ''),
    skuId: String(item.skuId || ''),
    title: item.skuName || item.spuName || '未命名商品',
    brand: item.brandName || '未设置',
    category: item.categoryName || '未设置',
    price: Number(item.price || 0),
    stock: Number(item.stock || 0),
    rating: 4.5,
    tags: [item.categoryName || '商城商品'],
    desc: `${item.spuName || item.skuName || '商城商品'}，来自商城实时商品搜索。`
  }
}

function productToAgentProduct(item) {
  if (!item) {
    return null
  }
  return {
    productId: String(item.spuId || item.skuId || ''),
    skuId: String(item.skuId || ''),
    title: item.skuName || item.spuName || '未命名商品',
    brand: item.brandName || '未设置',
    category: item.categoryName || '未设置',
    price: Number(item.price || 0),
    stock: Number(item.stock || 0),
    rating: Number(item.reviewSummary?.averageRating || 4.5),
    tags: [item.categoryName || '商城商品', item.promotion || '常规商品'].filter(Boolean),
    desc: item.reviewSummary?.latestReview || item.promotion || '来自商品详情接口。'
  }
}

function applyAssistantProductHints(content) {
  const ids = new Set(String(content || '').match(/\b\d{3,}\b/g) || [])
  if (!ids.size) {
    return
  }
  const matched = agent.products.filter((item) => ids.has(String(item.skuId)) || ids.has(String(item.productId)))
  const rest = agent.products.filter((item) => !matched.includes(item))
  agent.products = [...matched, ...rest]
  persistAgentState()
}

function onImagesSelected(event) {
  const files = Array.from(event.target.files || []).filter((file) => file.type.startsWith('image/'))
  selectedImages.value = selectedImages.value.concat(files).slice(0, 4)
  event.target.value = ''
}

function removeSelectedImage(index) {
  selectedImages.value.splice(index, 1)
}

async function createDisplayMediaUrls(files, imageUrl) {
  const urls = files.map((file) => URL.createObjectURL(file))
  if (imageUrl) {
    urls.push(imageUrl)
  }
  return urls
}

function createAgentMessage(role, content, pending = false, mediaUrls = []) {
  return {
    id: crypto.randomUUID(),
    role,
    content,
    modelId: agent.modelId,
    pending,
    mediaUrls,
    createdAt: new Date().toISOString()
  }
}

function scrollChatToBottom() {
  nextTick(() => {
    if (chatLogRef.value) {
      chatLogRef.value.scrollTop = chatLogRef.value.scrollHeight
    }
  })
}

function agentHeaders(includeMallToken = true) {
  const headers = {
    Authorization: `Basic ${encodeBase64(`${loginForm.username || session.username || ''}:${loginForm.password || ''}`)}`
  }
  if (includeMallToken && session.token) {
    headers['X-Mall-Authorization'] = `Bearer ${session.token}`
  }
  return headers
}

function cleanParams(params) {
  return Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== '' && value !== null && value !== undefined)
  )
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

function formatNumber(value) {
  return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
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

function categoryOptions(nodes = categoryTree.value, level = 0) {
  return nodes.flatMap((node) => [
    {
      id: node.categoryId,
      label: `${'　'.repeat(level)}${node.categoryName}`
    },
    ...categoryOptions(node.children || [], level + 1)
  ])
}

function createSessionId() {
  return `shopping-${Math.random().toString(36).slice(2, 10)}`
}

function normalizeBaseUrl(value) {
  return (value || 'http://localhost:8081').trim().replace(/\/+$/, '')
}

function encodeBase64(value) {
  const bytes = new TextEncoder().encode(value)
  let binary = ''
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte)
  })
  return btoa(binary)
}

async function createFetchError(response) {
  const text = (await response.text()).trim()
  return new Error(text || `请求失败：HTTP ${response.status}`)
}

function humanizeFetchError(error) {
  if (error?.name === 'AbortError') {
    return '请求已停止'
  }
  return error?.message || '请求失败，请检查 RAGAgent、商城后端和跨域配置。'
}

onMounted(async () => {
  await Promise.allSettled([
    loadProduct(),
    searchProductList(),
    loadCategoryTree(),
    loadSeckillActivities(),
    loadAgentModels(false)
  ])
  if (isLoggedIn.value) {
    await Promise.allSettled([refreshCart(), refreshAgentProducts(false)])
  }
})
</script>

<template>
  <main class="app-shell">
    <header class="topbar">
      <div class="topbar-inner">
        <button class="brand" type="button" @click="navigate('agent')">
          <span class="brand-mark">
            <el-icon :size="22"><ShoppingBag /></el-icon>
          </span>
          <span>
            <span class="brand-title">Mall Guide Console</span>
            <span class="brand-subtitle">商城业务链路 + 导购 Agent 工作台</span>
          </span>
        </button>

        <nav class="nav-tabs" aria-label="页面导航">
          <button
            v-for="item in navItems"
            :key="item.key"
            class="nav-tab"
            :class="{ 'is-active': route === item.key }"
            type="button"
            @click="navigate(item.key)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </button>
        </nav>

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
      <div class="page-head">
        <div>
          <p class="eyebrow">当前页面</p>
          <h1>{{ currentTitle }}</h1>
          <p>{{ currentPageDescription }}</p>
        </div>
        <div class="page-actions">
          <el-button :icon="Refresh" plain @click="refreshCart">刷新购物车</el-button>
          <el-button type="primary" :icon="ChatDotRound" @click="navigate('agent')">去导购</el-button>
        </div>
      </div>

      <section v-show="route === 'agent'" class="agent-layout">
        <aside class="stack">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><User /></el-icon>
                  连接设置
                </h2>
                <el-tag effect="plain">RAGAgent</el-tag>
              </div>
            </template>

            <el-form label-position="top" @submit.prevent="handleLogin">
              <el-form-item label="商城账号">
                <el-input v-model="loginForm.username" autocomplete="username" />
              </el-form-item>
              <el-form-item label="商城密码">
                <el-input v-model="loginForm.password" type="password" autocomplete="current-password" show-password />
              </el-form-item>
              <el-form-item label="导购服务地址">
                <el-input v-model="agent.ragBaseUrl" placeholder="http://localhost:8081" @change="persistAgentState" />
              </el-form-item>
              <el-form-item label="会话 ID">
                <div class="inline-field">
                  <el-input v-model="agent.sessionId" @change="persistAgentState" />
                  <el-button :icon="Refresh" plain @click="resetAgentSession" />
                </div>
              </el-form-item>
              <el-form-item label="模型">
                <el-select v-model="agent.modelId" placeholder="后端默认模型" @change="persistAgentState">
                  <el-option label="后端默认模型" value="" />
                  <el-option
                    v-for="model in agent.modelOptions"
                    :key="model.id"
                    :label="`${model.label || model.id} (${model.model || model.id})`"
                    :value="model.id"
                  />
                </el-select>
              </el-form-item>
              <div class="button-row">
                <el-button type="primary" :icon="Select" :loading="loading.login" @click="handleLogin">
                  登录商城
                </el-button>
                <el-button :icon="Connection" plain @click="loadAgentModels(true)">刷新模型</el-button>
                <el-button :type="agent.webSearchEnabled ? 'success' : 'default'" plain @click="toggleWebSearch">
                  联网搜索
                </el-button>
              </div>
            </el-form>

            <div class="status-banner">{{ agent.status }}</div>
          </el-card>

          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><CirclePlus /></el-icon>
                  快捷需求
                </h2>
              </div>
            </template>
            <div class="prompt-list">
              <button v-for="prompt in promptChips" :key="prompt" class="prompt-chip" type="button" @click="askPrompt(prompt)">
                {{ prompt }}
              </button>
            </div>
          </el-card>
        </aside>

        <section class="chat-column">
          <el-card class="panel chat-panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><ChatDotRound /></el-icon>
                  导购对话
                </h2>
                <div class="toolbar">
                  <el-button text @click="clearAgentMessages">清空</el-button>
                  <el-button :icon="ShoppingCart" plain @click="navigate('cart')">
                    购物车 {{ agentCartCount }}
                  </el-button>
                </div>
              </div>
            </template>

            <div ref="chatLogRef" class="chat-log">
              <div v-if="!agent.messages.length" class="empty-box">
                输入预算、品类、用途、品牌偏好，或上传商品图，让导购给出候选商品和下一步操作。
              </div>
              <article
                v-for="message in agent.messages"
                v-else
                :key="message.id"
                class="message"
                :class="[`message-${message.role}`, { 'is-pending': message.pending }]"
              >
                <div class="message-header">
                  <span>{{ message.role === 'user' ? '你' : '导购 Agent' }}</span>
                  <span>{{ formatTime(message.createdAt) }}</span>
                </div>
                <div v-if="message.mediaUrls?.length" class="message-media">
                  <img v-for="url in message.mediaUrls" :key="url" :src="url" alt="导购图片" />
                </div>
                <div class="message-bubble">{{ message.content || '正在等待模型返回内容' }}</div>
              </article>
            </div>

            <div class="composer">
              <el-input
                v-model="agent.messageInput"
                type="textarea"
                :rows="4"
                placeholder="例如：预算 300 元内，通勤降噪耳机，优先库存充足和好评"
                @keydown.ctrl.enter.prevent="sendAgentMessage"
              />
              <div class="upload-strip">
                <input ref="imageInputRef" type="file" accept="image/*" multiple @change="onImagesSelected" />
                <el-button :icon="Picture" plain @click="imageInputRef?.click()">上传图片</el-button>
                <el-input v-model="agent.imageUrl" placeholder="图片 URL，可选" clearable />
              </div>
              <div v-if="selectedImages.length" class="image-preview-list">
                <div v-for="(file, index) in selectedImages" :key="`${file.name}-${index}`" class="image-preview">
                  <img :src="URL.createObjectURL(file)" :alt="file.name" />
                  <button type="button" @click="removeSelectedImage(index)">×</button>
                </div>
              </div>
              <div class="button-row">
                <el-button type="primary" :icon="ArrowRight" :loading="loading.agent" @click="sendAgentMessage">
                  发送
                </el-button>
                <el-button :disabled="!loading.agent" plain @click="stopAgentMessage">停止</el-button>
              </div>
            </div>
          </el-card>
        </section>

        <aside class="stack">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><Goods /></el-icon>
                  导购候选
                </h2>
                <el-button :icon="Refresh" plain :loading="loading.agentProducts" @click="refreshAgentProducts(true)">刷新</el-button>
              </div>
            </template>
            <div v-if="agentProducts.length" class="agent-product-list">
              <article v-for="item in agentProducts" :key="`${item.productId}-${item.skuId}`" class="agent-product">
                <div class="product-visual">{{ item.category.slice(0, 2) }}</div>
                <div class="agent-product-main">
                  <h3>{{ item.title }}</h3>
                  <p>{{ item.brand }} / SKU {{ item.skuId }}</p>
                  <strong>{{ formatMoney(item.price) }}</strong>
                  <div class="mini-actions">
                    <el-button size="small" text type="primary" @click="loadProduct(item.skuId, true)">详情</el-button>
                    <el-button size="small" text @click="compareProduct(item)">对比</el-button>
                    <el-button size="small" text @click="addProductToCart({ skuId: item.skuId, skuName: item.title, price: item.price }, 1)">加购</el-button>
                  </div>
                </div>
              </article>
            </div>
            <div v-else class="empty-box">暂无候选商品</div>
          </el-card>

          <el-card class="panel compare-panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">对比视图</h2>
                <el-button text @click="clearCompare">清空</el-button>
              </div>
            </template>
            <div v-if="comparedProducts.length" class="compare-table-wrap">
              <table class="compare-table">
                <thead>
                  <tr>
                    <th>维度</th>
                    <th v-for="item in comparedProducts" :key="item.productId">{{ item.title }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <th>价格</th>
                    <td v-for="item in comparedProducts" :key="item.productId">{{ formatMoney(item.price) }}</td>
                  </tr>
                  <tr>
                    <th>库存</th>
                    <td v-for="item in comparedProducts" :key="item.productId">{{ item.stock }}</td>
                  </tr>
                  <tr>
                    <th>品牌</th>
                    <td v-for="item in comparedProducts" :key="item.productId">{{ item.brand }}</td>
                  </tr>
                  <tr>
                    <th>依据</th>
                    <td v-for="item in comparedProducts" :key="item.productId">{{ item.desc }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else class="empty-box">从候选商品里选择“对比”</div>
          </el-card>
        </aside>
      </section>

      <section v-show="route === 'products'" class="grid">
        <div class="stack">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><Search /></el-icon>
                  商品搜索
                </h2>
                <el-button :icon="Refresh" plain :loading="loading.search" @click="searchProductList">刷新</el-button>
              </div>
            </template>

            <div class="search-form">
              <el-input v-model="productQuery.keyword" placeholder="关键词" clearable @keyup.enter="searchProductList" />
              <el-select v-model="productQuery.categoryId" placeholder="类目" clearable>
                <el-option v-for="item in categoryOptions()" :key="item.id" :label="item.label" :value="item.id" />
              </el-select>
              <el-input v-model="productQuery.brand" placeholder="品牌" clearable />
              <el-input-number v-model="productQuery.minPrice" :min="0" placeholder="最低价" controls-position="right" />
              <el-input-number v-model="productQuery.maxPrice" :min="0" placeholder="最高价" controls-position="right" />
              <el-button type="primary" :icon="Search" :loading="loading.search" @click="searchProductList">
                搜索
              </el-button>
            </div>

            <div v-if="productResults.length" class="table-wrap" style="margin-top: 14px">
              <el-table :data="productResults" border>
                <el-table-column prop="skuId" label="SKU" width="100" />
                <el-table-column prop="skuName" label="商品" min-width="190" />
                <el-table-column prop="brandName" label="品牌" width="120" />
                <el-table-column prop="categoryName" label="类目" width="120" />
                <el-table-column label="价格" width="110">
                  <template #default="{ row }">{{ formatMoney(row.price) }}</template>
                </el-table-column>
                <el-table-column prop="stock" label="库存" width="80" />
                <el-table-column label="操作" width="230" fixed="right">
                  <template #default="{ row }">
                    <el-button text type="primary" @click="loadProduct(row.skuId)">详情</el-button>
                    <el-button text @click="askAboutProduct(row)">追问</el-button>
                    <el-button text @click="addSearchItemToCart(row)">加购</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <div v-else class="empty-box" style="margin-top: 14px">暂无商品搜索结果</div>
          </el-card>

          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><Goods /></el-icon>
                  商品详情
                </h2>
                <div class="toolbar">
                  <el-tag effect="plain">默认 SKU 1001</el-tag>
                  <el-button :icon="Refresh" plain :loading="loading.product" @click="loadProduct()">刷新</el-button>
                </div>
              </div>
            </template>

            <div class="query-row">
              <el-input-number v-model="skuId" :min="1" controls-position="right" />
              <el-button type="primary" :icon="Search" :loading="loading.product" @click="loadProduct()">
                查询商品
              </el-button>
              <el-button type="success" :icon="ShoppingCart" :loading="loading.addCart" @click="addProductToCart()">
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

              <div class="status-strip">
                <el-tag v-if="product.promotion" type="success" effect="plain">{{ product.promotion }}</el-tag>
                <el-tag v-if="product.reviewSummary" effect="plain">
                  评分 {{ product.reviewSummary.averageRating }} / {{ product.reviewSummary.reviewCount }} 条评价
                </el-tag>
              </div>

              <div v-if="product.coupons?.length" class="coupon-list">
                <el-tag v-for="coupon in product.coupons" :key="coupon.couponId" type="warning" effect="light">
                  {{ coupon.title }}：满 {{ formatNumber(coupon.thresholdAmount) }} 减 {{ formatNumber(coupon.discountAmount) }}
                </el-tag>
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
                      <el-button text type="primary" @click="loadProduct(row.skuId)">选择</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </template>

            <div v-else class="empty-box" style="margin-top: 14px">暂无商品数据</div>
          </el-card>
        </div>

        <aside class="stack">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">导购动作</h2>
              </div>
            </template>
            <div class="action-list">
              <el-button :icon="ChatDotRound" @click="product && askAboutProduct({ ...product, skuName: product.skuName })">
                带当前商品去追问
              </el-button>
              <el-button :icon="ShoppingCart" @click="navigate('cart')">查看购物车</el-button>
              <el-button :icon="Tickets" @click="navigate('orders')">去下单</el-button>
            </div>
          </el-card>
        </aside>
      </section>

      <section v-show="route === 'cart'" class="grid">
        <div class="stack">
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
        </div>

        <aside class="stack">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">下一步</h2>
              </div>
            </template>
            <div class="action-list">
              <el-button type="primary" :icon="DocumentChecked" :loading="loading.order" @click="handleConfirmOrder(true)">
                确认订单
              </el-button>
              <el-button :icon="ChatDotRound" plain @click="askAboutCart">让导购检查</el-button>
              <el-button :icon="Goods" plain @click="navigate('products')">继续选购</el-button>
            </div>
          </el-card>
        </aside>
      </section>

      <section v-show="route === 'orders'" class="grid">
        <div class="stack">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">
                  <el-icon><Tickets /></el-icon>
                  订单
                </h2>
                <div class="toolbar">
                  <el-button :icon="DocumentChecked" plain :loading="loading.order" @click="handleConfirmOrder()">
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
              <div v-if="currentOrder.items?.length" class="table-wrap" style="margin-top: 12px">
                <el-table :data="currentOrder.items" border size="small">
                  <el-table-column prop="skuId" label="SKU" width="100" />
                  <el-table-column prop="skuName" label="商品" />
                  <el-table-column prop="quantity" label="数量" width="80" />
                  <el-table-column label="金额" width="120">
                    <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </el-card>
        </div>

        <aside class="stack">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">页面跳转</h2>
              </div>
            </template>
            <div class="action-list">
              <el-button :icon="ShoppingCart" @click="navigate('cart')">回购物车</el-button>
              <el-button :icon="Lightning" @click="navigate('seckill')">查看秒杀</el-button>
              <el-button :icon="ChatDotRound" @click="navigate('agent')">回导购</el-button>
            </div>
          </el-card>
        </aside>
      </section>

      <section v-show="route === 'seckill'" class="grid">
        <div class="stack">
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
              <el-button :icon="Search" :loading="loading.seckill" @click="handleQuerySeckillResult()">查结果</el-button>
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
        </div>

        <aside class="stack">
          <el-card class="panel">
            <template #header>
              <div class="panel-header">
                <h2 class="panel-title">导购联动</h2>
              </div>
            </template>
            <div class="action-list">
              <el-button :icon="ChatDotRound" @click="askPrompt('帮我判断当前秒杀活动里哪些商品值得抢，并说明库存和下单风险。'); navigate('agent')">
                让导购分析秒杀
              </el-button>
              <el-button :icon="Tickets" @click="navigate('orders')">订单页</el-button>
            </div>
          </el-card>
        </aside>
      </section>
    </section>
  </main>
</template>
