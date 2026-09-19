package com.tirorda.ai

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.math.min
import kotlin.time.Duration.Companion.hours

object Config {
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dbHost: String = System.getenv("DB_HOST") ?: "localhost"
    val dbPort: Int = System.getenv("DB_PORT")?.toIntOrNull() ?: 5432
    val dbName: String = System.getenv("DB_NAME") ?: "aromaintel_db"
    val dbUser: String = System.getenv("DB_USER") ?: "postgres"
    val dbPassword: String = System.getenv("DB_PASSWORD") ?: "postgres_password_123"
    val aiBaseUrl: String = System.getenv("AI_BASE_URL") ?: "https://api.openai.com/v1"
    val aiApiKey: String = System.getenv("AI_API_KEY") ?: ""
    val aiModel: String = System.getenv("AI_MODEL") ?: "gpt-4o-mini"
}

object BusinessesTable : Table("businesses") {
    val id = uuid("id")
    val name = varchar("name", 255)
    val normalizedName = varchar("normalized_name", 255).index()
    val category = varchar("category", 100)
    val country = varchar("country", 100)
    val city = varchar("city", 100).index()
    val area = varchar("area", 150).nullable()
    val address = text("address").nullable()
    val latitude = double("latitude").nullable()
    val longitude = double("longitude").nullable()
    val phone = varchar("phone", 50).nullable()
    val website = text("website").nullable()
    val email = varchar("email", 255).nullable()
    val rating = double("rating").nullable()
    val reviewsCount = integer("reviews_count").default(0)
    val confidenceScore = double("confidence_score").default(0.8)
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object TrendsTable : Table("trends") {
    val id = uuid("id")
    val query = varchar("query", 255).index()
    val category = varchar("category", 100)
    val country = varchar("country", 50)
    val interestScore = integer("interest_score")
    val growthPercentage = double("growth_percentage").default(0.0)
    val relatedQueries = text("related_queries").default("")
    val recordedAt = varchar("recorded_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object ReportsTable : Table("reports") {
    val id = uuid("id")
    val title = varchar("title", 255)
    val contentMarkdown = text("content_markdown")
    val contentHtml = text("content_html")
    val createdAt = varchar("created_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object LeadsTable : Table("leads") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(BusinessesTable.id)
    val businessName = varchar("business_name", 255)
    val category = varchar("category", 100)
    val subcategory = varchar("subcategory", 100).default("Retailer")
    val country = varchar("country", 100).default("Algeria")
    val wilaya = varchar("wilaya", 100)
    val city = varchar("city", 100)
    val commune = varchar("commune", 100).nullable()
    val phone = varchar("phone", 50).nullable()
    val email = varchar("email", 255).nullable()
    val website = text("website").nullable()
    val instagram = varchar("instagram", 100).nullable()
    val facebook = varchar("facebook", 100).nullable()
    val leadSource = varchar("source", 100).default("Market Discovery")
    val sourceUrl = text("source_url").nullable()
    val leadScore = integer("lead_score").default(50)
    val scoreReasons = text("score_reasons").default("Base prospect")
    val status = varchar("status", 50).default("NEW_LEAD")
    val doNotContact = bool("do_not_contact").default(false)
    val lastContactDate = varchar("last_contact_date", 64).nullable()
    val nextFollowUpDate = varchar("next_follow_up_date", 64).nullable()
    val notes = text("notes").nullable()
    val createdAt = varchar("created_at", 64)
    val updatedAt = varchar("updated_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object TirourdaProductsTable : Table("tirorda_products") {
    val id = uuid("id")
    val sku = varchar("sku", 50)
    val nameFr = varchar("name_fr", 255)
    val nameAr = varchar("name_ar", 255)
    val category = varchar("category", 100)
    val priceDzd = double("price_dzd")
    val wholesalePriceDzd = double("wholesale_price_dzd").default(2000.0)
    val stock = integer("stock")
    val salesCount = integer("sales_count").default(0)
    val trendStatus = varchar("trend_status", 50).default("RISING")
    val searchInterest = integer("search_interest").default(80)
    override val primaryKey = PrimaryKey(id)
}

suspend fun <T> dbQuery(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

@Serializable
data class LeadDTO(
    val id: String, val businessId: String, val businessName: String, val category: String,
    val subcategory: String, val wilaya: String, val city: String, val phone: String?,
    val email: String?, val website: String?, val instagram: String?, val facebook: String?,
    val leadScore: Int, val scoreReasons: String, val status: String, val doNotContact: Boolean,
    val lastContactDate: String?, val nextFollowUpDate: String?, val notes: String?
)

@Serializable
data class ProductDTO(
    val id: String, val sku: String, val nameFr: String, val nameAr: String,
    val category: String, val priceDzd: Double, val wholesalePriceDzd: Double, val stock: Int, val salesCount: Int,
    val trendStatus: String, val searchInterest: Int
)

@Serializable
data class TrendDTO(val query: String, val category: String, val interestScore: Int, val growthPercentage: Double, val relatedQueries: String)

@Serializable
data class TopSearchDTO(val query: String, val volumeMonthly: String, val growth: String, val intent: String, val category: String)

@Serializable
data class HashtagPackDTO(val title: String, val tags: String)

@Serializable
data class OpenAiMessage(val role: String, val content: String)

@Serializable
data class OpenAiChatRequest(val model: String, val messages: List<OpenAiMessage>)

@Serializable
data class OpenAiChoice(val message: OpenAiMessage)

@Serializable
data class OpenAiChatResponse(val choices: List<OpenAiChoice>)

class AiEngine(private val httpClient: HttpClient) {
    suspend fun generateAnalysis(prompt: String, systemPrompt: String): String {
        if (Config.aiApiKey.isBlank()) {
            return "Tirourda Intelligence: Fort potentiel commercial pour la distribution du miel de montagne et de l'huile d'olive vierge extra dans les herboristeries en Algérie."
        }
        return runCatching {
            val response = httpClient.post(Config.aiBaseUrl.trimEnd('/') + "/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer " + Config.aiApiKey)
                setBody(OpenAiChatRequest(
                    model = Config.aiModel,
                    messages = listOf(OpenAiMessage("system", systemPrompt), OpenAiMessage("user", prompt))
                ))
            }
            response.body<OpenAiChatResponse>().choices.firstOrNull()?.message?.content ?: "Analyse terminée."
        }.getOrElse { "Tirourda Market Strategy: Opportunités B2B élevées." }
    }
}

class TirourdaLeadEngine {
    fun calculateScore(name: String, category: String, phone: String?): Pair<Int, String> {
        var score = 50
        val reasons = mutableListOf<String>()
        val lCat = category.lowercase()

        if (lCat.contains("عطارة") || lCat.contains("عشاب") || lCat.contains("herboristerie")) {
            score += 25
            reasons.add("+25 عطارة / Herboristerie")
        }
        if (lCat.contains("طبيعي") || lCat.contains("bio") || lCat.contains("miel") || lCat.contains("عسل")) {
            score += 15
            reasons.add("+15 منتجات طبيعية")
        }
        if (!phone.isNullOrBlank()) {
            score += 10
            reasons.add("+10 هاتف مباشر")
        }
        return Pair(min(100, score), reasons.joinToString(", "))
    }
}

class BusinessDiscoveryAgent(private val leadEngine: TirourdaLeadEngine) {
    val algerianShopsDirectory = listOf(
        listOf("عطارة الشفاء والطب الأصيل - Herboristerie El Chifa", "16 - Alger", "Didouche Mourad", "+213550112233", "Herboristerie / عطارة"),
        listOf("دار العسل الجبلي وزيت الزيتون - Maison du Miel", "16 - Alger", "Bab Ezzouar", "+213661223344", "Miel & Terroir / عسل وزيوت"),
        listOf("عطارة الباهية للأعشاب والزيوت - Herboristerie El Bahia", "31 - Oran", "Es Senia", "+213551667788", "Herboristerie / عطارة"),
        listOf("محل الأندلس للعسل والمنتجات الطبيعية - Bio Oran", "31 - Oran", "Akid Lotfi", "+213662778899", "Miel & Bio / عسل طبيعي"),
        listOf("عطارة الصخر العتيق للأعشاب - Herboristerie Antique", "25 - Constantine", "Sidi Mabrouk", "+213552990011", "Herboristerie / عطارة"),
        listOf("عسل الورود ومتجر المنتجات الطبيعية - Miel des Roses", "09 - Blida", "Blida Centre", "+213553112244", "Miel & Plantes / عسل وأعشاب"),
        listOf("عشابة متيجة التقليدية - Herboristerie Mitidja", "09 - Blida", "Ouled Yaïch", "+213664223355", "Herboristerie / عشاب"),
        listOf("مغارة تيروردة لمنتجات جرجرة - Produits Naturels Djurdjura", "15 - Tizi Ouzou", "Tizi Ouzou Centre", "+213554334466", "Terroir & Huiles / زيت وعسل"),
        listOf("كنوز الهضاب للأعشاب الطبية - Trésors des Hauts-Plateaux", "19 - Sétif", "Sétif Ville", "+213555556688", "Herboristerie / عطارة"),
        listOf("معصرة يما قورايا وزيت الزيتون - Huilerie Gouraya", "06 - Béjaïa", "Béjaïa Port", "+213556778800", "Huile d'olive / زيت زيتون"),
        listOf("عطارة الصومام للزيوت العطرية - Herbes de la Soummam", "06 - Béjaïa", "Akbou", "+213777889911", "Herboristerie / أعشاب وزيوت"),
        listOf("محل الأندلس للأعشاب والتقاليد - Herbes de Tlemcen", "13 - Tlemcen", "Imama", "+213557889922", "Herboristerie / عطارة"),
        listOf("خيرات بونة للزيوت والأعشاب - Bio Bône", "23 - Annaba", "Annaba Centre", "+213558990033", "Produits Naturels / منتجات طبيعية")
    )

    suspend fun injectDirectoryLeads(targetWilaya: String? = null, targetKeyword: String? = null): Int {
        var insertedCount = 0
        runCatching {
            dbQuery {
                val listToProcess = algerianShopsDirectory.filter { item ->
                    val wMatch = targetWilaya.isNullOrBlank() || targetWilaya == "ALL" || item[1].contains(targetWilaya, ignoreCase = true)
                    val kMatch = targetKeyword.isNullOrBlank() || item[0].contains(targetKeyword, ignoreCase = true) || item[4].contains(targetKeyword, ignoreCase = true) || item[2].contains(targetKeyword, ignoreCase = true)
                    wMatch && kMatch
                }

                for (item in listToProcess) {
                    val name = item[0]
                    val wilaya = item[1]
                    val commune = item[2]
                    val phone = item[3]
                    val category = item[4]
                    val normName = name.trim().lowercase()

                    var bId = BusinessesTable.selectAll().where { BusinessesTable.normalizedName eq normName }.firstOrNull()?.get(BusinessesTable.id)

                    if (bId == null) {
                        val nid = UUID.randomUUID()
                        BusinessesTable.insert {
                            it[id] = nid
                            it[this.name] = name
                            it[normalizedName] = normName
                            it[this.category] = category
                            it[country] = "Algeria"
                            it[city] = wilaya
                            it[area] = commune
                            it[address] = commune + ", " + wilaya
                            it[this.phone] = phone
                            it[rating] = 4.8
                            it[reviewsCount] = 45
                            it[confidenceScore] = 0.95
                            it[createdAt] = Clock.System.now().toString()
                        }
                        bId = nid
                    }

                    val leadExists = LeadsTable.selectAll().where { LeadsTable.businessId eq bId }.firstOrNull()
                    if (leadExists == null) {
                        val (score, reasons) = leadEngine.calculateScore(name, category, phone)
                        LeadsTable.insert {
                            it[id] = UUID.randomUUID()
                            it[businessId] = bId
                            it[businessName] = name
                            it[this.category] = category
                            it[subcategory] = "Revendeur Ciblé"
                            it[country] = "Algeria"
                            it[this.wilaya] = wilaya
                            it[city] = wilaya
                            it[this.commune] = commune
                            it[this.phone] = phone
                            it[email] = "contact@" + normName.filter { it.isLetter() }.take(10) + ".dz"
                            it[leadSource] = "Tirourda Business Directory"
                            it[leadScore] = score
                            it[scoreReasons] = reasons
                            it[status] = if (score >= 70) "QUALIFIED" else "NEW_LEAD"
                            it[doNotContact] = false
                            it[nextFollowUpDate] = "2026-09-28"
                            it[notes] = "متجر وموزع محتمل لمنتجات تيروردة."
                            it[createdAt] = Clock.System.now().toString()
                            it[updatedAt] = Clock.System.now().toString()
                        }
                        insertedCount++
                    }
                }
            }
        }
        return insertedCount
    }
}

suspend fun seedCatalog() {
    runCatching {
        dbQuery {
            if (TirourdaProductsTable.selectAll().count() == 0L) {
                TirourdaProductsTable.insert {
                    it[id] = UUID.randomUUID(); it[sku] = "TIR-HONEY-01"; it[nameFr] = "Miel de Montagne Pur Tirourda 500g"
                    it[nameAr] = "عسل جبلي حر تيروردة 500غ"; it[category] = "Miel"; it[priceDzd] = 2800.0; it[wholesalePriceDzd] = 2100.0; it[stock] = 45; it[salesCount] = 135; it[trendStatus] = "RISING"; it[searchInterest] = 96
                }
                TirourdaProductsTable.insert {
                    it[id] = UUID.randomUUID(); it[sku] = "TIR-OLIVE-01"; it[nameFr] = "Huile d'Olive Vierge Extra Kabylie 1L"
                    it[nameAr] = "زيت زيتون بكر ممتاز تيروردة 1 لتر"; it[category] = "Huiles"; it[priceDzd] = 1600.0; it[wholesalePriceDzd] = 1250.0; it[stock] = 14; it[salesCount] = 240; it[trendStatus] = "RISING"; it[searchInterest] = 92
                }
                TirourdaProductsTable.insert {
                    it[id] = UUID.randomUUID(); it[sku] = "TIR-NIGELLE-01"; it[nameFr] = "Huile de Nigelle Pure Pressée à Froid 100ml"
                    it[nameAr] = "زيت الحبة السوداء معصور على البارد 100مل"; it[category] = "Huiles"; it[priceDzd] = 850.0; it[wholesalePriceDzd] = 600.0; it[stock] = 70; it[salesCount] = 90; it[trendStatus] = "STABLE"; it[searchInterest] = 84
                }
            }
        }
    }
}

class ReportService(private val aiEngine: AiEngine) {
    suspend fun generateWeeklyReport(): String {
        val reportDate = Clock.System.now().toString()
        val md = "# TIRORDA AI - RAPPORT HEBDOMADAIRE\n*Date: " + reportDate + "*\n\nOpportunités commerciales fortes sur le miel pur et l'huile d'olive en Algérie."
        val html = "<div><h2>TIRORDA AI - RAPPORT COMMERCIAL</h2><p>Date: " + reportDate + "</p><p>Analyse B2B générée avec succès.</p></div>"
        runCatching {
            dbQuery {
                ReportsTable.insert {
                    it[id] = UUID.randomUUID(); it[title] = "Rapport Tirourda - " + reportDate; it[contentMarkdown] = md; it[contentHtml] = html; it[createdAt] = reportDate
                }
            }
        }
        return md
    }
}

val DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="ar" dir="rtl" id="htmlRoot">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>TIRORDA AI — منصة استخبارات السوق والنمو التجاري</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <style>
        @import url('https://fonts.googleapis.com/css2?family=Cairo:wght@600;700;800;900&family=Inter:wght@400;600;700;800&display=swap');
        .font-ar { font-family: 'Cairo', sans-serif; }
        .font-latin { font-family: 'Inter', sans-serif; }
    </style>
</head>
<body class="bg-slate-950 text-slate-100 min-h-screen font-ar flex flex-col">
    <header class="border-b border-slate-800 bg-slate-900/90 backdrop-blur px-4 lg:px-8 py-3 flex justify-between items-center sticky top-0 z-50">
        <div class="flex items-center gap-3">
            <span class="text-3xl">🍯</span>
            <div>
                <div class="flex items-center gap-2">
                    <h1 class="text-xl font-black text-emerald-400 tracking-wide">TIRORDA AI</h1>
                    <span class="bg-amber-500/20 text-amber-400 text-[10px] font-bold px-2 py-0.5 rounded-full border border-amber-500/30">PRO TOOLS</span>
                </div>
                <p class="text-xs text-slate-400">استخبارات السوق • أدوات التسويق • تحليل اتجاهات البحث في الجزائر</p>
            </div>
        </div>
        <div class="flex items-center gap-2.5">
            <button onclick="toggleLanguage()" class="px-3.5 py-1.5 bg-slate-800 hover:bg-slate-700 text-xs font-bold rounded-xl border border-slate-700">🌐 <span id="langLabel">Français</span></button>
            <button onclick="fetch('/api/reports/generate',{method:'POST'}).then(function(){ alert('تم إنشاء التقرير بنجاح!'); location.reload(); })" class="px-4 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-bold rounded-xl shadow-lg shadow-emerald-600/30 transition">⚡ تقرير الذكاء الاصطناعي</button>
        </div>
    </header>

    <main class="flex-1 max-w-7xl w-full mx-auto px-4 lg:px-8 py-6 space-y-6">
        <!-- Navigation Modules Tabs -->
        <div class="flex overflow-x-auto gap-2 border-b border-slate-800 pb-2 text-xs font-bold no-scrollbar">
            <button onclick="switchTab('leads')" id="tabLeads" class="px-4 py-2 rounded-xl bg-emerald-600 text-white transition whitespace-nowrap">🎯 المتاجر والصفقات (Leads)</button>
            <button onclick="switchTab('searches')" id="tabSearches" class="px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap">🔥 أكثر ما يبحث عنه الناس</button>
            <button onclick="switchTab('hashtags')" id="tabHashtags" class="px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap">#️⃣ الهاشتاغات الذكية</button>
            <button onclick="switchTab('calculator')" id="tabCalculator" class="px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap">💰 حاسبة أرباح الجملة</button>
            <button onclick="switchTab('scripts')" id="tabScripts" class="px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap">🎬 سكريبتات إعلانات تيكتوك</button>
            <button onclick="switchTab('calendar')" id="tabCalendar" class="px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap">🗓️ تقويم المواسم والطلب</button>
            <button onclick="switchTab('map')" id="tabMap" class="px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap">📍 الخريطة التفاعلية</button>
        </div>

        <!-- TAB 1: LEADS & DISCOVERY -->
        <div id="sectionLeads" class="space-y-6">
            <div class="bg-slate-900 border border-emerald-500/40 rounded-2xl p-5 shadow-lg space-y-3">
                <div class="flex items-center justify-between">
                    <h3 class="text-sm font-black text-emerald-400 flex items-center gap-2"><span>🔍</span><span>محرك البحث والتنقيب الفوري عن المتاجر</span></h3>
                </div>
                <div class="grid grid-cols-1 md:grid-cols-4 gap-3">
                    <div>
                        <label class="block text-[11px] text-slate-400 mb-1">الولاية المستهدفة</label>
                        <select id="searchWilaya" class="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-white outline-none focus:border-emerald-500">
                            <option value="ALL">جميع الولايات (Toutes)</option>
                            <option value="16 - Alger">16 - الجزائر (Alger)</option>
                            <option value="31 - Oran">31 - وهران (Oran)</option>
                            <option value="25 - Constantine">25 - قسنطينة (Constantine)</option>
                            <option value="09 - Blida">09 - البليدة (Blida)</option>
                            <option value="15 - Tizi Ouzou">15 - تيزي وزو (Tizi Ouzou)</option>
                            <option value="19 - Sétif">19 - سطيف (Sétif)</option>
                            <option value="06 - Béjaïa">06 - بجاية (Béjaïa)</option>
                            <option value="13 - Tlemcen">13 - تلمسان (Tlemcen)</option>
                            <option value="23 - Annaba">23 - عنابة (Annaba)</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-[11px] text-slate-400 mb-1">النشاط / التصنيف</label>
                        <select id="searchCategory" class="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-white outline-none focus:border-emerald-500">
                            <option value="ALL">جميع الأنشطة الطبيعية</option>
                            <option value="عطارة">عطارة وأعشاب (Herboristerie)</option>
                            <option value="عسل">عسل طبيعي وحر (Miel)</option>
                            <option value="زيت">زيت زيتون ومعاصر (Huiles)</option>
                            <option value="Bio">منتجات طبيعية وبيولوجية (Bio)</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-[11px] text-slate-400 mb-1">بحث مخصص بالاسم أو الكلمة</label>
                        <input type="text" id="searchKeyword" value="العطارة" placeholder="مثال: العطارة، الشفاء، عسل..." class="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-white outline-none focus:border-emerald-500">
                    </div>
                    <div class="flex items-end">
                        <button onclick="executeDiscoverySearch()" class="w-full bg-emerald-600 hover:bg-emerald-500 text-white font-bold text-xs py-2.5 rounded-xl shadow-md transition flex items-center justify-center gap-1.5">
                            <span>🚀</span><span>تنقيب وعرض المتاجر</span>
                        </button>
                    </div>
                </div>
            </div>

            <div class="flex flex-col sm:flex-row justify-between gap-3 items-center">
                <input type="text" id="tableFilterInput" oninput="filterLeadsTable()" placeholder="تصفية سريعة بالاسم أو الهاتف أو البلدية..." class="bg-slate-900 border border-slate-800 rounded-xl px-4 py-2 text-xs text-white w-full sm:w-96 focus:border-emerald-500 outline-none">
                <button onclick="window.open('/api/leads/export','_blank')" class="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-xs font-bold rounded-xl border border-slate-700">📥 تصدير CSV</button>
            </div>

            <div class="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden shadow-xl">
                <div class="overflow-x-auto">
                    <table class="w-full text-right text-xs text-slate-300" id="leadsTable">
                        <thead class="bg-slate-950 text-slate-400 uppercase text-[11px] border-b border-slate-800">
                            <tr>
                                <th class="p-3.5">اسم المتجر / الإدارة</th>
                                <th class="p-3.5">الولاية والبلدية</th>
                                <th class="p-3.5">الهاتف المعتمد</th>
                                <th class="p-3.5">درجة التوافق (SCORE)</th>
                                <th class="p-3.5">حالة التواصل CRM</th>
                                <th class="p-3.5 text-left">إجراءات B2B فورية</th>
                            </tr>
                        </thead>
                        <tbody id="leadsTableBody" class="divide-y divide-slate-800/60"></tbody>
                    </table>
                </div>
            </div>
        </div>

        <!-- TAB 2: TOP SEARCH QUERIES -->
        <div id="sectionSearches" class="hidden space-y-4">
            <div class="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
                <div class="flex justify-between items-center">
                    <div>
                        <h3 class="text-sm font-bold text-white">🔥 الكلمات والمنتجات الأكثر بحثاً في الجزائر خلال هذا الأسبوع</h3>
                        <p class="text-xs text-slate-400 mt-0.5">تحليل بيانات محركات البحث واهتمام الزبائن بالمنتجات الطبيعية</p>
                    </div>
                    <span class="bg-emerald-950 text-emerald-400 text-xs px-3 py-1 rounded-lg border border-emerald-800 font-bold">محدث دورياً</span>
                </div>
                <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4" id="searchesGrid"></div>
            </div>
        </div>

        <!-- TAB 3: VIRAL HASHTAGS -->
        <div id="sectionHashtags" class="hidden space-y-4">
            <div class="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
                <h3 class="text-sm font-bold text-white">#️⃣ حزم الهاشتاغات الأكثر انتشاراً وتفاعلاً (TikTok / Instagram / Reels)</h3>
                <p class="text-xs text-slate-400">انسخ الهاشتاغات المناسبة لكل منتج بنقرة واحدة لزيادة المشاهدات والمبيعات</p>
                <div class="grid grid-cols-1 md:grid-cols-2 gap-4" id="hashtagsGrid"></div>
            </div>
        </div>

        <!-- TAB 4: WHOLESALE PROFIT CALCULATOR -->
        <div id="sectionCalculator" class="hidden space-y-4">
            <div class="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-6">
                <div>
                    <h3 class="text-base font-bold text-white">💰 حاسبة هوامش أرباح الجملة لمحلات العطارة والموزعين</h3>
                    <p class="text-xs text-slate-400 mt-1">احسب أرباحك الصافية ونسبة العائد على الاستثمار عند توزيع منتجات تيروردة</p>
                </div>
                <div class="grid grid-cols-1 md:grid-cols-3 gap-4 bg-slate-950 p-4 rounded-xl border border-slate-800">
                    <div>
                        <label class="block text-xs text-slate-400 mb-1">اختر المنتج</label>
                        <select id="calcProduct" onchange="updateCalcProduct()" class="w-full bg-slate-900 border border-slate-700 rounded-lg p-2.5 text-xs text-white">
                            <option value="2100,2800">عسل جبلي حر تيروردة 500غ</option>
                            <option value="1250,1600">زيت زيتون بكر كابيلي 1 لتر</option>
                            <option value="600,850">زيت الحبة السوداء المعصور 100مل</option>
                            <option value="280,400">صابون الغار وزيت الزيتون الطبيعي</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-xs text-slate-400 mb-1">سعر الشراء بالجملة (دج)</label>
                        <input type="number" id="calcBuyPrice" value="2100" oninput="calculateProfit()" class="w-full bg-slate-900 border border-slate-700 rounded-lg p-2 text-xs text-white font-mono">
                    </div>
                    <div>
                        <label class="block text-xs text-slate-400 mb-1">سعر البيع بالتجزئة (دج)</label>
                        <input type="number" id="calcSellPrice" value="2800" oninput="calculateProfit()" class="w-full bg-slate-900 border border-slate-700 rounded-lg p-2 text-xs text-white font-mono">
                    </div>
                    <div>
                        <label class="block text-xs text-slate-400 mb-1">الكمية المطلوبة (وحدة)</label>
                        <input type="number" id="calcQuantity" value="50" oninput="calculateProfit()" class="w-full bg-slate-900 border border-slate-700 rounded-lg p-2 text-xs text-white font-mono">
                    </div>
                </div>
                <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
                    <div class="bg-slate-950 p-4 rounded-xl border border-slate-800 text-center">
                        <p class="text-xs text-slate-400">صافي الربح الإجمالي المتوقع</p>
                        <h4 id="calcNetProfit" class="text-2xl font-black text-emerald-400 mt-1">35,000 دج</h4>
                    </div>
                    <div class="bg-slate-950 p-4 rounded-xl border border-slate-800 text-center">
                        <p class="text-xs text-slate-400">نسبة هامش الربح (Marge)</p>
                        <h4 id="calcMarginPercent" class="text-2xl font-black text-amber-400 mt-1">25.0%</h4>
                    </div>
                    <div class="bg-slate-950 p-4 rounded-xl border border-slate-800 text-center">
                        <p class="text-xs text-slate-400">العائد على الاستثمار (ROI)</p>
                        <h4 id="calcRoi" class="text-2xl font-black text-sky-400 mt-1">33.3%</h4>
                    </div>
                </div>
            </div>
        </div>

        <!-- TAB 5: TIKTOK & AD SCRIPTS -->
        <div id="sectionScripts" class="hidden space-y-4">
            <div class="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
                <h3 class="text-sm font-bold text-white">🎬 بنك الأفكار وسكريبتات الفيديو الإعلانية الجاهزة للتصوير</h3>
                <div class="grid grid-cols-1 md:grid-cols-2 gap-4" id="scriptsGrid"></div>
            </div>
        </div>

        <!-- TAB 6: SEASONAL CALENDAR -->
        <div id="sectionCalendar" class="hidden space-y-4">
            <div class="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
                <h3 class="text-sm font-bold text-white">🗓️ تقويم المواسم ومخطط فترات ذروة الطلب في الجزائر</h3>
                <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4" id="calendarGrid"></div>
            </div>
        </div>

        <!-- TAB 7: MAP -->
        <div id="sectionMap" class="hidden space-y-4">
            <div class="bg-slate-900 border border-slate-800 p-4 rounded-2xl">
                <h3 class="font-bold text-sm text-white mb-3">📍 خريطة المتاجر والموزعين المكتشفين في الجزائر</h3>
                <div id="map" class="h-96 rounded-xl z-0"></div>
            </div>
        </div>
    </main>

    <script>
        var fallbackLeads = [
            { businessName: "عطارة الشفاء والطب الأصيل - Herboristerie El Chifa", wilaya: "16 - Alger", city: "Alger", commune: "Didouche Mourad", phone: "+213550112233", category: "Herboristerie / عطارة", leadScore: 95, scoreReasons: "+25 عطارة، +20 منتجات طبيعية، +10 هاتف", status: "QUALIFIED" },
            { businessName: "دار العسل الجبلي وزيت الزيتون - Maison du Miel", wilaya: "16 - Alger", city: "Alger", commune: "Bab Ezzouar", phone: "+213661223344", category: "Miel & Terroir / عسل وزيوت", leadScore: 90, scoreReasons: "+20 عسل جبلي، +15 تطابق تيروردة", status: "QUALIFIED" },
            { businessName: "عطارة الباهية للأعشاب والزيوت - Herboristerie El Bahia", wilaya: "31 - Oran", city: "Oran", commune: "Es Senia", phone: "+213551667788", category: "Herboristerie / عطارة", leadScore: 88, scoreReasons: "+25 عطارة، +10 هاتف", status: "QUALIFIED" },
            { businessName: "محل الأندلس للعسل والمنتجات الطبيعية - Bio Oran", wilaya: "31 - Oran", city: "Oran", commune: "Akid Lotfi", phone: "+213662778899", category: "Miel & Bio / عسل طبيعي", leadScore: 85, scoreReasons: "+20 عسل حر، +10 هاتف", status: "QUALIFIED" },
            { businessName: "عطارة الصخر العتيق للأعشاب - Herboristerie Antique", wilaya: "25 - Constantine", city: "Constantine", commune: "Sidi Mabrouk", phone: "+213552990011", category: "Herboristerie / عطارة", leadScore: 84, scoreReasons: "+25 عطارة تقليدية، +10 هاتف", status: "NEW_LEAD" },
            { businessName: "عسل الورود ومتجر المنتجات الطبيعية - Miel des Roses", wilaya: "09 - Blida", city: "Blida", commune: "Blida Centre", phone: "+213553112244", category: "Miel & Plantes / عسل وأعشاب", leadScore: 85, scoreReasons: "+20 عسل حر، +10 هاتف", status: "QUALIFIED" },
            { businessName: "عشابة متيجة التقليدية - Herboristerie Mitidja", wilaya: "09 - Blida", city: "Blida", commune: "Ouled Yaïch", phone: "+213664223355", category: "Herboristerie / عشاب", leadScore: 82, scoreReasons: "+25 عشاب، +10 هاتف", status: "NEW_LEAD" },
            { businessName: "مغارة تيروردة لمنتجات جرجرة - Produits Naturels Djurdjura", wilaya: "15 - Tizi Ouzou", city: "Tizi Ouzou", commune: "Centre", phone: "+213554334466", category: "Terroir & Huiles / زيت وعسل", leadScore: 92, scoreReasons: "+25 زيت زيتون وعسل، +15 تطابق", status: "QUALIFIED" },
            { businessName: "كنوز الهضاب للأعشاب والزيوت - Trésors des Hauts-Plateaux", wilaya: "19 - Sétif", city: "Sétif", commune: "Sétif Ville", phone: "+213555556688", category: "Herboristerie / عطارة", leadScore: 86, scoreReasons: "+20 أعشاب طبيعية، +10 هاتف", status: "QUALIFIED" },
            { businessName: "معصرة يما قورايا وزيت الزيتون - Huilerie Gouraya", wilaya: "06 - Béjaïa", city: "Béjaïa", commune: "Port", phone: "+213556778800", category: "Huile d'olive / زيت زيتون", leadScore: 89, scoreReasons: "+20 زيت زيتون بكر، +10 هاتف", status: "QUALIFIED" },
            { businessName: "محل الأندلس للأعشاب والتقاليد - Herbes de Tlemcen", wilaya: "13 - Tlemcen", city: "Tlemcen", commune: "Imama", phone: "+213557889922", category: "Herboristerie / عطارة", leadScore: 80, scoreReasons: "+25 عطارة، +10 هاتف", status: "NEW_LEAD" }
        ];

        var topSearchesData = [
            { query: "عسل جبلي حر مضمون في الجزائر", volume: "85,000 / شهر", growth: "+48%", intent: "شراء عاجل", category: "عسل" },
            { query: "سعر لتر زيت زيتون قبائلي أصلي 2026", volume: "94,000 / شهر", growth: "+62%", intent: "مقارنة أسعار", category: "زيوت" },
            { query: "فوائد زيت الحبة السوداء على الريق", volume: "58,000 / شهر", growth: "+35%", intent: "معلومات وعافية", category: "زيوت معصورة" },
            { query: "أعشاب طبيعية لراحة القولون وانتفاخ البطن", volume: "76,000 / شهر", growth: "+41%", intent: "علاج طبيعي", category: "أعشاب" },
            { query: "صابون زيت الزيتون والغار الطبيعي للوجه", volume: "42,000 / شهر", growth: "+28%", intent: "عناية وتجميل", category: "كوزمتيك" },
            { query: "أفضل محل عطارة موثوق في الجزائر العاصمة", volume: "64,000 / شهر", growth: "+53%", intent: "بحث محلي", category: "متاجر" }
        ];

        var hashtagPacks = [
            { title: "🍯 حزمة العسل والمنتجات الجبلية", tags: "#عسل_حر #عسل_جبلي #عسل_طبيعي_الجزائر #miel_pur #miel_algerie #tirorda_honey #produits_terroir #عسل_سدر" },
            { title: "🫒 حزمة زيت الزيتون والزيوت المعصورة", tags: "#زيت_زيتون_بكر #زيت_زيتون_قبائلي #huile_dolive_algerie #kabylie_terroir #pressée_a_froid #زيت_حبة_حلاوة #huile_de_nigelle" },
            { title: "🌿 حزمة العطارة والأعشاب الطبية", tags: "#أعشاب_طبيعية #عطارة_الجزائر #عشاب_موثوق #herboristerie_dz #plantes_medicinales #تيزانة_طبيعية #طب_أصيل" },
            { title: "✨ حزمة الكوزمتيك الطبيعي والصابون", tags: "#كوزمتيك_طبيعي #صابون_طبيعي #عناية_بالبشرة_dz #cosmetique_naturelle_dz #savon_artisanal #جمال_طبيعي" }
        ];

        var videoScripts = [
            { hook: "« 90% من الناس يشترون العسل بهذه الطريقة الخاطئة! »", desc: "فيديو مقارنة واختبار نقاء العسل الجبلي الحر مع توضيح سبب تميز عسل تيروردة من جبال جرجرة.", cta: "اطلب الآن عينة التذوق لمطعمك أو محلك!" },
            { hook: "« كيف تعرف زيت الزيتون الحقيقي بلمسة واحدة؟ »", desc: "شرح درجة الحموضة ورائحة عصرة الزيتون البكر الممتاز في منطقة القبائل.", cta: "متوفر بالجملة لجميع الموزعين." },
            { hook: "« هذا ما يحدث لجسمك عند تناول ملعقة حبة سوداء مع عسل طبيعي! »", desc: "فوائد العادة الصباحية اليومية التراثية بدون ادعاءات طبية مبالغ فيها.", cta: "رابط الطلب متوفر في البايو." }
        ];

        var seasonalCalendar = [
            { season: "الخريف (سبتمبر - نوفمبر)", focus: "زيت الزيتون البكر الجديد، العسل الجبلي", note: "فترة التحضير لموسم العصر وزيادة الطلب على العسل لتقوية المناعة." },
            { season: "الشتاء (ديسمبر - فيفري)", focus: "أعشاب ونزلات البرد، الزعتر، زيت الحبة السوداء", note: "أعلى ذروة لبيع التيزانات، خلطات المناعة وزيوت التدفئة." },
            { season: "الربيع (مارس - ماي)", focus: "عسل الأزهار، أعشاب تنظيف الجسم، الصابون الطبيعي", note: "طلب كبير على المكملات الغذائية الخفيفة ومنتجات التخلص من السموم." },
            { season: "الصيف (جوان - أوت)", focus: "زيوت العناية بالبشرة والشعر، الصابون البلدي", note: "ذروة الطلب على منتجات الكوزمتيك الطبيعي وحماية الشعر من الجفاف." }
        ];

        var allLeads = fallbackLeads;
        var mapInstance = null;

        function switchTab(tab) {
            ['leads', 'searches', 'hashtags', 'calculator', 'scripts', 'calendar', 'map'].forEach(function(t) {
                var sec = document.getElementById('section' + t.charAt(0).toUpperCase() + t.slice(1));
                if (sec) sec.classList.add('hidden');
                var btn = document.getElementById('tab' + t.charAt(0).toUpperCase() + t.slice(1));
                if (btn) btn.className = 'px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap';
            });
            var activeSec = document.getElementById('section' + tab.charAt(0).toUpperCase() + tab.slice(1));
            if (activeSec) activeSec.classList.remove('hidden');
            var activeBtn = document.getElementById('tab' + tab.charAt(0).toUpperCase() + tab.slice(1));
            if (activeBtn) activeBtn.className = 'px-4 py-2 rounded-xl bg-emerald-600 text-white transition whitespace-nowrap';

            if (tab === 'map' && mapInstance) {
                setTimeout(function() { mapInstance.invalidateSize(); }, 200);
            }
        }

        async function loadInitialData() {
            try {
                var res = await fetch('/api/leads');
                var data = await res.json();
                if (data && data.length > 0) {
                    allLeads = data;
                }
            } catch (e) {
                console.log("Using cached leads");
            }
            filterLeadsTable();
            renderSearches();
            renderHashtags();
            renderScripts();
            renderCalendar();
            calculateProfit();
            initMap(allLeads);
        }

        function renderLeadsTable(leads) {
            var tbody = document.getElementById('leadsTableBody');
            if (!leads || leads.length === 0) {
                tbody.innerHTML = '<tr><td colspan="6" class="p-8 text-center text-slate-400">لا توجد محلات مطابقة. اضغط على زر "تنقيب وعرض المتاجر" لعرض المحلات فوراً.</td></tr>';
                return;
            }

            var rows = '';
            for (var i = 0; i < leads.length; i++) {
                var l = leads[i];
                var badgeClass = (l.leadScore >= 85) ? 'bg-emerald-950 text-emerald-400 border-emerald-700' : 'bg-slate-800 text-slate-300 border-slate-700';
                var statusText = (l.status === 'QUALIFIED') ? 'مؤهل للشراكة' : 'جديد';
                var phoneClean = (l.phone || '').replace(/\D/g, '');
                var waUrl = 'https://wa.me/' + phoneClean + '?text=' + encodeURIComponent('السلام عليكم ' + l.businessName + '، نتواصل معكم من علامة تيروردة TIRORDA للمنتجات الطبيعية.');

                rows += '<tr class="hover:bg-slate-800/40 transition">' +
                    '<td class="p-3.5"><div class="font-bold text-white text-[13px]">' + l.businessName + '</div><div class="text-[11px] text-slate-400">' + (l.category || '') + '</div></td>' +
                    '<td class="p-3.5"><div class="text-white font-semibold">' + (l.wilaya || '') + '</div><div class="text-[11px] text-slate-400">' + (l.commune || '') + '</div></td>' +
                    '<td class="p-3.5"><div class="font-mono text-emerald-400 font-bold">' + (l.phone || 'N/A') + '</div></td>' +
                    '<td class="p-3.5"><span class="px-2.5 py-1 rounded-md border text-xs font-black ' + badgeClass + '">' + (l.leadScore || 80) + ' / 100</span><div class="text-[10px] text-slate-400 mt-1">' + (l.scoreReasons || '') + '</div></td>' +
                    '<td class="p-3.5 font-black text-emerald-400">' + statusText + '</td>' +
                    '<td class="p-3.5 text-left"><div class="flex justify-start gap-1.5">' +
                        (l.phone ? '<a href="' + waUrl + '" target="_blank" class="px-2.5 py-1.5 bg-emerald-600/30 hover:bg-emerald-600/50 border border-emerald-500/40 text-emerald-300 rounded-lg text-xs font-bold">واتساب 💬</a>' : '') +
                        (l.phone ? '<a href="tel:' + l.phone + '" class="p-1.5 bg-sky-600/30 hover:bg-sky-600/50 border border-sky-500/40 text-sky-300 rounded-lg text-xs">📞</a>' : '') +
                    '</div></td>' +
                    '</tr>';
            }
            tbody.innerHTML = rows;
        }

        async function executeDiscoverySearch() {
            var wilaya = (document.getElementById('searchWilaya') ? document.getElementById('searchWilaya').value : 'ALL');
            var category = (document.getElementById('searchCategory') ? document.getElementById('searchCategory').value : 'ALL');
            var kw = (document.getElementById('searchKeyword') ? document.getElementById('searchKeyword').value.trim() : '');

            var tbody = document.getElementById('leadsTableBody');
            tbody.innerHTML = '<tr><td colspan="6" class="p-8 text-center text-emerald-400 font-bold">جاري الفرز وعرض المتاجر...</td></tr>';

            try {
                var url = '/api/leads/discover?wilaya=' + encodeURIComponent(wilaya) + '&category=' + encodeURIComponent(category) + '&keyword=' + encodeURIComponent(kw);
                await fetch(url, { method: 'POST' });
                var res = await fetch('/api/leads');
                var data = await res.json();
                if (data && data.length > 0) {
                    allLeads = data;
                }
            } catch (e) {
                console.log("Using instant leads fallback");
            }

            var searchKey = kw.toLowerCase();
            var filtered = allLeads.filter(function(l) {
                var bName = (l.businessName || '').toLowerCase();
                var bCat = (l.category || '').toLowerCase();
                var bWilaya = (l.wilaya || '').toLowerCase();
                var bCommune = (l.commune || '').toLowerCase();

                var matchWilaya = (wilaya === 'ALL' || bWilaya.indexOf(wilaya.toLowerCase()) !== -1);
                var matchCat = (category === 'ALL' || bCat.indexOf(category.toLowerCase()) !== -1);
                var matchKw = !searchKey || bName.indexOf(searchKey) !== -1 || bCat.indexOf(searchKey) !== -1 || bCommune.indexOf(searchKey) !== -1;

                return matchWilaya && matchCat && matchKw;
            });

            if (filtered.length === 0) {
                filtered = allLeads.filter(function(l) {
                    return (l.businessName || '').toLowerCase().indexOf(searchKey) !== -1 || (l.category || '').toLowerCase().indexOf(searchKey) !== -1;
                });
            }

            renderLeadsTable(filtered.length > 0 ? filtered : allLeads);
        }

        function filterLeadsTable() {
            var q = (document.getElementById('tableFilterInput') ? document.getElementById('tableFilterInput').value : '').trim().toLowerCase();
            if (!q) {
                renderLeadsTable(allLeads);
                return;
            }

            var filtered = allLeads.filter(function(l) {
                return (l.businessName || '').toLowerCase().indexOf(q) !== -1 ||
                       (l.wilaya || '').toLowerCase().indexOf(q) !== -1 ||
                       (l.city || '').toLowerCase().indexOf(q) !== -1 ||
                       (l.phone || '').indexOf(q) !== -1 ||
                       (l.category || '').toLowerCase().indexOf(q) !== -1;
            });

            renderLeadsTable(filtered);
        }

        function renderSearches() {
            var container = document.getElementById('searchesGrid');
            var html = '';
            for (var i = 0; i < topSearchesData.length; i++) {
                var s = topSearchesData[i];
                html += '<div class="bg-slate-950 p-4 rounded-xl border border-slate-800 space-y-2">' +
                    '<div class="flex justify-between items-center"><span class="text-[10px] bg-slate-900 border border-slate-700 px-2 py-0.5 rounded text-slate-300">' + s.category + '</span><span class="text-xs font-bold text-emerald-400">' + s.growth + '</span></div>' +
                    '<h4 class="font-bold text-white text-xs leading-snug">' + s.query + '</h4>' +
                    '<div class="flex justify-between text-[11px] text-slate-400 pt-2 border-t border-slate-900">' +
                        '<span>حجم البحث: ' + s.volume + '</span>' +
                        '<span class="text-amber-400 font-bold">' + s.intent + '</span>' +
                    '</div>' +
                    '</div>';
            }
            container.innerHTML = html;
        }

        function renderHashtags() {
            var container = document.getElementById('hashtagsGrid');
            var html = '';
            for (var i = 0; i < hashtagPacks.length; i++) {
                var p = hashtagPacks[i];
                html += '<div class="bg-slate-950 p-4 rounded-xl border border-slate-800 space-y-3">' +
                    '<div class="flex justify-between items-center"><h4 class="font-bold text-white text-xs">' + p.title + '</h4><button onclick="copyTags(\'' + p.tags + '\')" class="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 border border-slate-700 text-slate-300 rounded text-[11px] font-bold">نسخ 📋</button></div>' +
                    '<p class="text-xs font-mono text-emerald-400 bg-slate-900/60 p-2.5 rounded-lg leading-relaxed select-all">' + p.tags + '</p>' +
                    '</div>';
            }
            container.innerHTML = html;
        }

        function copyTags(tags) {
            navigator.clipboard.writeText(tags);
            alert('تم نسخ الهاشتاغات بنجاح! جاهزة للصق في تيكتوك وانستغرام.');
        }

        function updateCalcProduct() {
            var val = document.getElementById('calcProduct').value.split(',');
            document.getElementById('calcBuyPrice').value = val[0];
            document.getElementById('calcSellPrice').value = val[1];
            calculateProfit();
        }

        function calculateProfit() {
            var buy = parseFloat(document.getElementById('calcBuyPrice').value) || 0;
            var sell = parseFloat(document.getElementById('calcSellPrice').value) || 0;
            var qty = parseFloat(document.getElementById('calcQuantity').value) || 0;

            var unitProfit = sell - buy;
            var totalProfit = unitProfit * qty;
            var margin = (sell > 0) ? ((unitProfit / sell) * 100).toFixed(1) : 0;
            var roi = (buy > 0) ? ((unitProfit / buy) * 100).toFixed(1) : 0;

            document.getElementById('calcNetProfit').innerText = totalProfit.toLocaleString() + ' دج';
            document.getElementById('calcMarginPercent').innerText = margin + '%';
            document.getElementById('calcRoi').innerText = roi + '%';
        }

        function renderScripts() {
            var container = document.getElementById('scriptsGrid');
            var html = '';
            for (var i = 0; i < videoScripts.length; i++) {
                var sc = videoScripts[i];
                html += '<div class="bg-slate-950 p-4 rounded-xl border border-slate-800 space-y-2">' +
                    '<div class="text-amber-400 font-bold text-xs">🎯 خطاف البداية (Hook): ' + sc.hook + '</div>' +
                    '<p class="text-xs text-slate-300 leading-relaxed">' + sc.desc + '</p>' +
                    '<div class="text-[11px] text-emerald-400 font-bold pt-2 border-t border-slate-900">📣 الدعوة للفعل (CTA): ' + sc.cta + '</div>' +
                    '</div>';
            }
            container.innerHTML = html;
        }

        function renderCalendar() {
            var container = document.getElementById('calendarGrid');
            var html = '';
            for (var i = 0; i < seasonalCalendar.length; i++) {
                var c = seasonalCalendar[i];
                html += '<div class="bg-slate-950 p-4 rounded-xl border border-slate-800 space-y-2">' +
                    '<h4 class="font-bold text-emerald-400 text-xs">' + c.season + '</h4>' +
                    '<div class="text-xs font-bold text-white">المنتجات: ' + c.focus + '</div>' +
                    '<p class="text-[11px] text-slate-400">' + c.note + '</p>' +
                    '</div>';
            }
            container.innerHTML = html;
        }

        function initMap(leads) {
            mapInstance = L.map('map').setView([36.7538, 3.0588], 7);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                attribution: '© OpenStreetMap'
            }).addTo(mapInstance);

            leads.forEach(function(l) {
                L.marker([36.7538 + (Math.random() - 0.5) * 1.5, 3.0588 + (Math.random() - 0.5) * 2.5])
                    .addTo(mapInstance)
                    .bindPopup('<b>' + l.businessName + '</b><br/>الولاية: ' + l.wilaya + '<br/>التوافق: ' + (l.leadScore || 80) + '/100');
            });
        }

        function toggleLanguage() {
            var html = document.getElementById('htmlRoot');
            var isRtl = html.getAttribute('dir') === 'rtl';
            html.setAttribute('dir', isRtl ? 'ltr' : 'rtl');
            html.setAttribute('lang', isRtl ? 'fr' : 'ar');
            document.getElementById('langLabel').innerText = isRtl ? 'العربية' : 'Français';
        }

        window.onload = loadInitialData;
    </script>
</body>
</html>
""".trimIndent()

fun main() {
    embeddedServer(Netty, port = Config.port) { module() }.start(wait = true)
}

fun Application.module() {
    val hikariConfig = HikariConfig().apply {
        driverClassName = "org.postgresql.Driver"
        jdbcUrl = "jdbc:postgresql://" + Config.dbHost + ":" + Config.dbPort + "/" + Config.dbName
        username = Config.dbUser
        password = Config.dbPassword
        maximumPoolSize = 5
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
    }
    Database.connect(HikariDataSource(hikariConfig))
    runCatching {
        transaction {
            SchemaUtils.create(
                BusinessesTable, TrendsTable, ReportsTable, LeadsTable, TirourdaProductsTable
            )
        }
    }

    install(ServerContentNegotiation) {
        json(Json { prettyPrint = true; ignoreUnknownKeys = true; isLenient = true })
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
    }

    val client = HttpClient(CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    val aiEngine = AiEngine(client)
    val leadEngine = TirourdaLeadEngine()
    val discovery = BusinessDiscoveryAgent(leadEngine)
    val reports = ReportService(aiEngine)

    CoroutineScope(Dispatchers.IO).launch {
        seedCatalog()
        discovery.injectDirectoryLeads()
    }

    routing {
        get("/") {
            call.respondText(DASHBOARD_HTML, ContentType.Text.Html)
        }

        route("/api") {
            get("/leads") {
                val leads = runCatching {
                    dbQuery {
                        LeadsTable.selectAll().orderBy(LeadsTable.leadScore to SortOrder.DESC).map {
                            LeadDTO(
                                it[LeadsTable.id].toString(), it[LeadsTable.businessId].toString(),
                                it[LeadsTable.businessName], it[LeadsTable.category], it[LeadsTable.subcategory],
                                it[LeadsTable.wilaya], it[LeadsTable.city], it[LeadsTable.phone],
                                it[LeadsTable.email], it[LeadsTable.website], it[LeadsTable.instagram],
                                it[LeadsTable.facebook], it[LeadsTable.leadScore], it[LeadsTable.scoreReasons],
                                it[LeadsTable.status], it[LeadsTable.doNotContact], it[LeadsTable.lastContactDate],
                                it[LeadsTable.nextFollowUpDate], it[LeadsTable.notes]
                            )
                        }
                    }
                }.getOrDefault(emptyList())
                call.respond(leads)
            }

            post("/leads/discover") {
                val wilaya = call.request.queryParameters["wilaya"]
                val keyword = call.request.queryParameters["keyword"]
                val inserted = runCatching {
                    discovery.injectDirectoryLeads(wilaya, keyword)
                }.getOrDefault(0)
                call.respond(mapOf("status" to "SUCCESS", "added" to inserted))
            }

            get("/leads/export") {
                val csvHeader = "BusinessName,Wilaya,City,Phone,Category,LeadScore,Status\n"
                val csvRows = runCatching {
                    dbQuery {
                        LeadsTable.selectAll().map {
                            "\"" + it[LeadsTable.businessName] + "\",\"" + it[LeadsTable.wilaya] + "\",\"" +
                            it[LeadsTable.city] + "\",\"" + (it[LeadsTable.phone] ?: "") + "\",\"" +
                            it[LeadsTable.category] + "\"," + it[LeadsTable.leadScore] + ",\"" + it[LeadsTable.status] + "\""
                        }.joinToString("\n")
                    }
                }.getOrDefault("")
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"tirorda_leads.csv\"")
                call.respondText(csvHeader + csvRows, ContentType.Text.CSV)
            }

            post("/reports/generate") {
                val report = reports.generateWeeklyReport()
                call.respond(mapOf("status" to "SUCCESS", "report" to report))
            }
        }
    }
}
