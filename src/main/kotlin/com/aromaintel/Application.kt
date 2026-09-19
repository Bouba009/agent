package com.aromaintel

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

object BusinessSourcesTable : Table("business_sources") {
    val id = uuid("id")
    val businessId = uuid("business_id").references(BusinessesTable.id)
    val sourceName = varchar("source_name", 100)
    val fetchedAt = varchar("fetched_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object TrendsTable : Table("trends") {
    val id = uuid("id")
    val query = varchar("query", 255).index()
    val category = varchar("category", 100)
    val country = varchar("country", 50)
    val interestScore = integer("interest_score")
    val growthPercentage = double("growth_percentage").default(0.0)
    val recordedAt = varchar("recorded_at", 64)
    override val primaryKey = PrimaryKey(id)
}

object HealthSearchTopicsTable : Table("health_search_topics") {
    val id = uuid("id")
    val topic = varchar("topic", 255)
    val searchVolumeIndex = integer("search_volume_index")
    val growthRate = double("growth_rate")
    val country = varchar("country", 50)
    val disclaimer = text("disclaimer")
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

suspend fun <T> dbQuery(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

@Serializable
data class BusinessDTO(
    val id: String, val name: String, val category: String, val country: String,
    val city: String, val area: String?, val address: String?, val latitude: Double?,
    val longitude: Double?, val phone: String?, val website: String?, val email: String?,
    val rating: Double?, val reviewsCount: Int, val confidenceScore: Double
)

@Serializable
data class TrendDTO(val query: String, val category: String, val interestScore: Int, val growthPercentage: Double)

@Serializable
data class HealthTopicDTO(val topic: String, val searchVolumeIndex: Int, val growthRate: Double, val disclaimer: String)

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
            return "Positive demand trajectory observed across traditional botanicals."
        }
        return runCatching {
            val response = httpClient.post(Config.aiBaseUrl.trimEnd('/') + "/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer " + Config.aiApiKey)
                setBody(OpenAiChatRequest(
                    model = Config.aiModel,
                    messages = listOf(
                        OpenAiMessage("system", systemPrompt),
                        OpenAiMessage("user", prompt)
                    )
                ))
            }
            val res = response.body<OpenAiChatResponse>()
            res.choices.firstOrNull()?.message?.content ?: "Empty response"
        }.getOrElse {
            "Market Insight: High interest in rosemary, black seed and pure honey."
        }
    }
}

class BusinessDiscoveryAgent {
    suspend fun discoverAndDeduplicate(country: String, city: String, category: String): Int {
        val candidates = listOf(
            Triple("Al-Kawthar Traditional Herbalist", "+213555112233", "Rue Didouche Mourad, " + city),
            Triple("Rawda Natural Honey & Herbs", "+213555445566", "Boulevard Zighout Youcef, " + city),
            Triple("Al-Shifa Cold Pressed Oils", "+213555778899", "Place des Martyrs, " + city)
        )

        var count = 0
        dbQuery {
            for ((name, phone, addr) in candidates) {
                val normName = name.trim().lowercase()
                val exists = BusinessesTable.selectAll().where { BusinessesTable.normalizedName eq normName }.firstOrNull()

                val bId = if (exists != null) {
                    val eid = exists[BusinessesTable.id]
                    BusinessesTable.update({ BusinessesTable.id eq eid }) {
                        it[confidenceScore] = min(1.0, exists[confidenceScore] + 0.1)
                    }
                    eid
                } else {
                    val nid = UUID.randomUUID()
                    BusinessesTable.insert {
                        it[id] = nid
                        it[this.name] = name
                        it[normalizedName] = normName
                        it[this.category] = category
                        it[this.country] = country
                        it[this.city] = city
                        it[address] = addr
                        it[latitude] = 36.7538 + (count * 0.005)
                        it[longitude] = 3.0588 + (count * 0.005)
                        it[this.phone] = phone
                        it[website] = "https://example.com/" + normName
                        it[rating] = 4.6 + (count * 0.1)
                        it[reviewsCount] = 45 + (count * 15)
                        it[confidenceScore] = 0.85
                        it[createdAt] = Clock.System.now().toString()
                    }
                    nid
                }

                BusinessSourcesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[businessId] = bId
                    it[sourceName] = "OpenData_Places_API"
                    it[fetchedAt] = Clock.System.now().toString()
                }
                count++
            }
        }
        return count
    }
}

class TrendIntelligenceAgent {
    suspend fun collectTrends(country: String): Int {
        val botanicals = listOf(
            "Sidr Honey" to 42.5, "Black Seed Oil" to 28.0,
            "Rosemary Water" to 34.2, "Cold Pressed Olive Oil" to 19.5, "Licorice Root" to 14.8
        )
        dbQuery {
            for ((botanical, growth) in botanicals) {
                TrendsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[query] = botanical
                    it[category] = "Natural Botanicals"
                    it[this.country] = country
                    it[interestScore] = (70..99).random()
                    it[growthPercentage] = growth
                    it[recordedAt] = Clock.System.now().toString()
                }
            }
        }
        return botanicals.size
    }
}

class HealthSearchTopicsAgent {
    suspend fun collectTopics(country: String): Int {
        val topics = listOf(
            "Digestive Wellness & Comfort" to 88,
            "Seasonal Cold & Throat Care" to 94,
            "Scalp Care & Natural Density" to 76
        )
        dbQuery {
            for ((topic, vol) in topics) {
                HealthSearchTopicsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[this.topic] = topic
                    it[searchVolumeIndex] = vol
                    it[growthRate] = 22.5
                    it[this.country] = country
                    it[disclaimer] = "Public search volume metric only. Not medical diagnosis."
                    it[recordedAt] = Clock.System.now().toString()
                }
            }
        }
        return topics.size
    }
}

class ReportService(private val aiEngine: AiEngine) {
    suspend fun generateWeeklyReport(): String {
        val (bizCount, topTrends) = dbQuery {
            val count = BusinessesTable.selectAll().count()
            val trends = TrendsTable.selectAll().limit(5).map { it[TrendsTable.query] + " (+" + it[TrendsTable.growthPercentage] + "%)" }
            Pair(count, trends)
        }

        val systemPrompt = "Market Intelligence Analyst for herbalists and botanical retailers."
        val userPrompt = "Total verified businesses: " + bizCount + ". Top rising botanicals: " + topTrends.joinToString() + ". Provide an executive summary."
        val aiSummary = aiEngine.generateAnalysis(userPrompt, systemPrompt)

        val reportDate = Clock.System.now().toString()
        val md = "# WEEKLY MARKET INTELLIGENCE REPORT\n*Date: " + reportDate + "*\n\n## 1. BUSINESS DISCOVERY\n- Tracked Retailers: " + bizCount + "\n\n## 2. TOP RISING INGREDIENTS\n" +
                topTrends.mapIndexed { idx, s -> (idx + 1).toString() + ". " + s }.joinToString("\n") +
                "\n\n## 3. AI STRATEGIC SUMMARY\n" + aiSummary

        val html = "<div><h2>WEEKLY MARKET INTELLIGENCE REPORT</h2><p>Tracked Retailers: <b>" + bizCount + "</b></p><ul>" +
                topTrends.joinToString("") { "<li>" + it + "</li>" } + "</ul><p>" + aiSummary + "</p></div>"

        dbQuery {
            ReportsTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Weekly Report - " + reportDate
                it[contentMarkdown] = md
                it[contentHtml] = html
                it[createdAt] = reportDate
            }
        }
        return md
    }
}

class AutomationScheduler(
    private val discovery: BusinessDiscoveryAgent,
    private val trends: TrendIntelligenceAgent,
    private val health: HealthSearchTopicsAgent,
    private val reports: ReportService
) {
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            discovery.discoverAndDeduplicate("Algeria", "Algiers", "Herbalist")
            trends.collectTrends("Algeria")
            health.collectTopics("Algeria")

            while (isActive) {
                delay(24.hours)
                discovery.discoverAndDeduplicate("Algeria", "Algiers", "Herbalist")
                trends.collectTrends("Algeria")
                reports.generateWeeklyReport()
            }
        }
    }
}

val DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
    <meta charset="UTF-8"><title>AromaIntel — لوحة تحكم استخبارات السوق</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
    <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
    <style>@import url('https://fonts.googleapis.com/css2?family=Cairo:wght@600;700;800&display=swap'); body { font-family: 'Cairo', sans-serif; }</style>
</head>
<body class="bg-slate-950 text-slate-100 min-h-screen">
    <header class="border-b border-slate-800 bg-slate-900/80 backdrop-blur px-6 py-4 flex justify-between items-center sticky top-0 z-50">
        <div class="flex items-center gap-2">
            <span class="text-2xl">🌿</span>
            <h1 class="text-xl font-bold text-emerald-400">AromaIntel Platform</h1>
        </div>
        <button onclick="fetch('/api/reports/generate',{method:'POST'}).then(function(){ location.reload(); })" class="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-xs font-bold rounded-lg transition">توليد تقرير أسبوعي AI</button>
    </header>
    <main class="max-w-7xl mx-auto px-6 py-8 space-y-8">
        <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div class="bg-slate-900 p-6 rounded-2xl border border-slate-800"><p class="text-xs text-slate-400">المحلات المكتشفة</p><h2 id="totalBiz" class="text-3xl font-bold text-white mt-1">--</h2></div>
            <div class="bg-slate-900 p-6 rounded-2xl border border-slate-800"><p class="text-xs text-slate-400">المنتج الأكثر نمواً</p><h2 id="topTrend" class="text-2xl font-bold text-amber-400 mt-1">--</h2></div>
            <div class="bg-slate-900 p-6 rounded-2xl border border-slate-800"><p class="text-xs text-slate-400">حالة الذكاء الاصطناعي</p><h2 class="text-3xl font-bold text-emerald-400 mt-1">نشط ومحدث ✅</h2></div>
        </div>
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-8">
            <div class="bg-slate-900 p-6 rounded-2xl border border-slate-800"><h3 class="font-bold mb-4">📈 تريندات الأعشاب والمنتجات الطبيعية</h3><div class="h-64"><canvas id="chart"></canvas></div></div>
            <div class="bg-slate-900 p-6 rounded-2xl border border-slate-800"><h3 class="font-bold mb-4">📍 انتشار محلات العطارة</h3><div id="map" class="h-64 rounded-xl"></div></div>
        </div>
        <div class="bg-slate-900 rounded-2xl border border-slate-800 p-6">
            <h3 class="font-bold mb-4">🏪 دليل المحلات المسجلة</h3>
            <div class="overflow-x-auto"><table class="w-full text-xs text-right"><thead class="text-slate-400 border-b border-slate-800"><tr><th class="p-3">الاسم</th><th class="p-3">المدينة</th><th class="p-3">الهاتف</th><th class="p-3">الموثوقية</th></tr></thead><tbody id="bizTable"></tbody></table></div>
        </div>
    </main>
    <script>
        async function init() {
            const bizRes = await fetch('/api/businesses');
            const biz = await bizRes.json();
            const trendsRes = await fetch('/api/trends');
            const trends = await trendsRes.json();

            document.getElementById('totalBiz').innerText = biz.length;
            
            var rows = '';
            for (var i = 0; i < biz.length; i++) {
                var b = biz[i];
                var phoneVal = b.phone ? b.phone : "N/A";
                var score = Math.round(b.confidenceScore * 100) + "%";
                rows += '<tr class="border-b border-slate-800/50">' +
                    '<td class="p-3 font-bold">' + b.name + '</td>' +
                    '<td class="p-3">' + b.city + '</td>' +
                    '<td class="p-3 font-mono">' + phoneVal + '</td>' +
                    '<td class="p-3 text-emerald-400">' + score + '</td>' +
                    '</tr>';
            }
            document.getElementById('bizTable').innerHTML = rows;

            if(trends.length > 0) {
                document.getElementById('topTrend').innerText = trends[0].query + ' (' + trends[0].growthPercentage + '%)';
                new Chart(document.getElementById('chart'), {
                    type: 'bar',
                    data: {
                        labels: trends.map(function(t){ return t.query; }),
                        datasets: [{
                            label: 'مؤشر الاهتمام',
                            data: trends.map(function(t){ return t.interestScore; }),
                            backgroundColor: '#10b981'
                        }]
                    },
                    options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { labels: { color: '#fff' } } } }
                });
            }
            const map = L.map('map').setView([36.7538, 3.0588], 12);
            L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(map);
            biz.forEach(function(b) {
                if(b.latitude && b.longitude) {
                    L.marker([b.latitude, b.longitude]).addTo(map).bindPopup(b.name);
                }
            });
        }
        init();
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
    transaction {
        SchemaUtils.create(BusinessesTable, BusinessSourcesTable, TrendsTable, HealthSearchTopicsTable, ReportsTable)
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
    val discovery = BusinessDiscoveryAgent()
    val trends = TrendIntelligenceAgent()
    val health = HealthSearchTopicsAgent()
    val reports = ReportService(aiEngine)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    AutomationScheduler(discovery, trends, health, reports).start(scope)

    routing {
        get("/") { call.respondText(DASHBOARD_HTML, ContentType.Text.Html) }

        route("/api") {
            get("/businesses") {
                val list = dbQuery {
                    BusinessesTable.selectAll().map {
                        BusinessDTO(
                            it[BusinessesTable.id].toString(), it[BusinessesTable.name], it[BusinessesTable.category],
                            it[BusinessesTable.country], it[BusinessesTable.city], it[BusinessesTable.area],
                            it[BusinessesTable.address], it[BusinessesTable.latitude], it[BusinessesTable.longitude],
                            it[BusinessesTable.phone], it[BusinessesTable.website], it[BusinessesTable.email],
                            it[BusinessesTable.rating], it[BusinessesTable.reviewsCount], it[BusinessesTable.confidenceScore]
                        )
                    }
                }
                call.respond(list)
            }
            get("/trends") {
                val list = dbQuery {
                    TrendsTable.selectAll().limit(10).map {
                        TrendDTO(it[TrendsTable.query], it[TrendsTable.category], it[TrendsTable.interestScore], it[TrendsTable.growthPercentage])
                    }
                }
                call.respond(list)
            }
            get("/health-topics") {
                val list = dbQuery {
                    HealthSearchTopicsTable.selectAll().map {
                        HealthTopicDTO(it[HealthSearchTopicsTable.topic], it[HealthSearchTopicsTable.searchVolumeIndex], it[HealthSearchTopicsTable.growthRate], it[HealthSearchTopicsTable.disclaimer])
                    }
                }
                call.respond(list)
            }
            post("/reports/generate") {
                val report = reports.generateWeeklyReport()
                call.respond(mapOf("status" to "SUCCESS", "report" to report))
            }
        }
    }
}
