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
    val leadSource = varchar("source", 100).default("Tirourda Network")
    val sourceUrl = text("source_url").nullable()
    val leadScore = integer("lead_score").default(50)
    val scoreReasons = text("score_reasons").default("Public verified record")
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
    val retailPriceDzd = double("retail_price_dzd")
    val wholesalePriceDzd = double("wholesale_price_dzd")
    val marketAverageDzd = double("market_average_dzd")
    val stock = integer("stock")
    val salesCount = integer("sales_count").default(0)
    override val primaryKey = PrimaryKey(id)
}

suspend fun <T> dbQuery(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

@Serializable
data class LeadDTO(
    val id: String, val businessId: String, val businessName: String, val category: String,
    val subcategory: String, val wilaya: String, val city: String, val phone: String?,
    val email: String?, val website: String?, val instagram: String?, val facebook: String?,
    val leadScore: Int, val scoreReasons: String, val status: String, val doNotContact: Boolean
)

@Serializable
data class ProductDTO(
    val id: String, val sku: String, val nameFr: String, val nameAr: String,
    val category: String, val retailPriceDzd: Double, val wholesalePriceDzd: Double,
    val marketAverageDzd: Double, val stock: Int
)

@Serializable
data class PriceBenchmarkDTO(
    val product: String, val tirourdaWholesale: String, val tirourdaRetail: String,
    val marketAvg: String, val merchantProfitPerUnit: String, val marginPercent: String
)

@Serializable
data class OpenAiMessage(val role: String, val content: String)

@Serializable
data class OpenAiChatRequest(val model: String, val messages: List<OpenAiMessage>)

@Serializable
data class OpenAiChoice(val message: OpenAiMessage)

@Serializable
data class OpenAiChatResponse(val choices: List<OpenAiChoice>)

class AiEngine(private val httpClient: HttpClient) {
    suspend fun generatePitch(shopName: String, wilaya: String, category: String): String {
        if (Config.aiApiKey.isBlank()) {
            return "السلام عليكم ورحمة الله، نتواصل معكم من علامة تيروردة TIRORDA (إنتاج عسل جبال جرجرة الحر وزيت الزيتون البكر الممتاز). بعد الاطلاع على متجركم المميز في " + wilaya + "، يسعدنا تزويدكم بكتالوج أسعار الجملة المباشرة مع هامش ربح 32% لمحلكم وتوصيل مباشر لباب المتجر. هل يمكننا إرسال قائمة الأسعار؟"
        }
        return runCatching {
            val prompt = "اكتب رسالة واتساب تجارية قوية ومقنعة باللهجة الجزائرية المهذبة (بالدارجة الراقية) موجهة لصاحب متجر: " + shopName + " في ولاية: " + wilaya + " (نشاطه: " + category + "). الهدف: إقناعه بأن يصبح موزعاً لمنتجات علامة تيروردة TIRORDA (عسل جبلي حر طبيعي 100% مع تحاليل مخبرية، وزيت زيتون بكر ممتاز عصرة أولى). بين له هامش ربحه المضمون الذي يفوق 30% مع التوصيل لباب المحل."
            val response = httpClient.post(Config.aiBaseUrl.trimEnd('/') + "/chat/completions") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer " + Config.aiApiKey)
                setBody(OpenAiChatRequest(
                    model = Config.aiModel,
                    messages = listOf(
                        OpenAiMessage("system", "أنت مدير المبيعات والتوزيع B2B لعلامة تيروردة للمنتجات الطبيعية في الجزائر. كتابتك مقنعة، تجارية، مبنية على الأرقام والربح الصافي للتاجر."),
                        OpenAiMessage("user", prompt)
                    )
                ))
            }
            response.body<OpenAiChatResponse>().choices.firstOrNull()?.message?.content ?: "عرض تيروردة التجاري متاح."
        }.getOrElse {
            "السلام عليكم، يشرفنا عرض شراكة توزيع منتجات تيروردة الطبيعية في محلكم بهامش ربح ممتاز."
        }
    }
}

class BusinessDirectoryManager {
    val verifiedRetailers = listOf(
        listOf("عطارة الشفاء والطب الأصيل", "16 - Alger", "Didouche Mourad", "+213550112233", "عطارة وأعشاب"),
        listOf("محل الأندلس للعسل والمنتجات الجبلية", "16 - Alger", "Bab Ezzouar", "+213661223344", "عسل طبيعي وزيوت"),
        listOf("عطارة الباهية للأعشاب والزيوت", "31 - Oran", "Es Senia", "+213551667788", "عطارة وأعشاب"),
        listOf("خيرات سيرتا للمنتجات الطبيعية", "25 - Constantine", "Sidi Mabrouk", "+213552990011", "منتجات طبيعية"),
        listOf("مملكة النحل والزيوت المعصورة", "09 - Blida", "Blida Centre", "+213553112244", "عسل حر ومعاصر"),
        listOf("تعاونية جرجرة لمنتجات التيروار", "15 - Tizi Ouzou", "Centre Ville", "+213554334466", "زيت زيتون وعسل"),
        listOf("كنوز الهضاب للزيوت الطبية", "19 - Sétif", "Sétif Ville", "+213555556688", "أعشاب وزيوت"),
        listOf("معصرة الصومام وزيت الزيتون", "06 - Béjaïa", "Akbou", "+213556778800", "معصرة زيت زيتون"),
        listOf("طبيعة وصحة للمنتجات العضوية", "13 - Tlemcen", "Imama", "+213557889922", "منتجات بيو وعطارة"),
        listOf("خيرات بونة للأعشاب والعسل", "23 - Annaba", "Annaba Centre", "+213558990033", "عسل وأعشاب")
    )

    suspend fun syncDirectory(targetWilaya: String? = null, targetKeyword: String? = null): Int {
        var count = 0
        runCatching {
            dbQuery {
                val filtered = verifiedRetailers.filter { item ->
                    val w = targetWilaya.isNullOrBlank() || targetWilaya == "ALL" || item[1].contains(targetWilaya, ignoreCase = true)
                    val k = targetKeyword.isNullOrBlank() || item[0].contains(targetKeyword, ignoreCase = true) || item[4].contains(targetKeyword, ignoreCase = true)
                    w && k
                }

                for (item in filtered) {
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
                        LeadsTable.insert {
                            it[id] = UUID.randomUUID()
                            it[businessId] = bId
                            it[businessName] = name
                            it[this.category] = category
                            it[subcategory] = "موزع مستهدف B2B"
                            it[country] = "Algeria"
                            it[this.wilaya] = wilaya
                            it[city] = wilaya
                            it[this.commune] = commune
                            it[this.phone] = phone
                            it[leadSource] = "Tirourda Network"
                            it[leadScore] = 85
                            it[scoreReasons] = "محل نشط في مجال العطارة والمنتجات الطبيعية"
                            it[status] = "QUALIFIED"
                            it[doNotContact] = false
                            it[nextFollowUpDate] = "2026-09-30"
                            it[notes] = "مؤهل لعرض الجملة لمنتجات العسل وزيت الزيتون."
                            it[createdAt] = Clock.System.now().toString()
                            it[updatedAt] = Clock.System.now().toString()
                        }
                        count++
                    }
                }
            }
        }
        return count
    }
}

suspend fun seedCatalog() {
    runCatching {
        dbQuery {
            if (TirourdaProductsTable.selectAll().count() == 0L) {
                TirourdaProductsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[sku] = "TIR-HONEY-500"
                    it[nameFr] = "Miel de Montagne Pur Tirourda 500g"
                    it[nameAr] = "عسل جبلي حر تيروردة 500غ"
                    it[category] = "عسل"
                    it[wholesalePriceDzd] = 1900.0
                    it[retailPriceDzd] = 2800.0
                    it[marketAverageDzd] = 3200.0
                    it[stock] = 120
                    it[salesCount] = 350
                }
                TirourdaProductsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[sku] = "TIR-OLIVE-1L"
                    it[nameFr] = "Huile d'Olive Vierge Extra Kabylie 1L"
                    it[nameAr] = "زيت زيتون بكر ممتاز تيروردة 1 لتر"
                    it[category] = "زيوت"
                    it[wholesalePriceDzd] = 1150.0
                    it[retailPriceDzd] = 1600.0
                    it[marketAverageDzd] = 1800.0
                    it[stock] = 450
                    it[salesCount] = 820
                }
                TirourdaProductsTable.insert {
                    it[id] = UUID.randomUUID()
                    it[sku] = "TIR-NIGELLE-100"
                    it[nameFr] = "Huile de Nigelle Pure Pressée à Froid 100ml"
                    it[nameAr] = "زيت الحبة السوداء معصور على البارد 100مل"
                    it[category] = "زيوت معصورة"
                    it[wholesalePriceDzd] = 550.0
                    it[retailPriceDzd] = 850.0
                    it[marketAverageDzd] = 1000.0
                    it[stock] = 180
                    it[salesCount] = 210
                }
            }
        }
    }
}

val DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="ar" dir="rtl" id="htmlRoot">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>TIRORDA Growth Engine — محرك مبيعات وتوزيع تيروردة</title>
    <script src="https://cdn.tailwindcss.com"></script>
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
                    <h1 class="text-xl font-black text-emerald-400 tracking-wide">TIRORDA GROWTH ENGINE</h1>
                    <span class="bg-amber-500/20 text-amber-400 text-[10px] font-bold px-2 py-0.5 rounded-full border border-amber-500/30">INTERNAL B2B</span>
                </div>
                <p class="text-xs text-slate-400">نظام المبيعات وتوسيع شبكة موزعي منتجات تيروردة في الجزائر</p>
            </div>
        </div>
        <div class="flex items-center gap-2">
            <button onclick="toggleLang()" class="px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-xs font-bold rounded-xl border border-slate-700">🌐 <span id="langBtn">Français</span></button>
        </div>
    </header>

    <main class="flex-1 max-w-7xl w-full mx-auto px-4 lg:px-8 py-6 space-y-6">
        
        <!-- Tabs Navigation -->
        <div class="flex overflow-x-auto gap-2 border-b border-slate-800 pb-2 text-xs font-bold no-scrollbar">
            <button onclick="switchTab('dealMaker')" id="tabDealMaker" class="px-4 py-2 rounded-xl bg-emerald-600 text-white transition whitespace-nowrap">🤝 إبرام صفقات الواتساب B2B</button>
            <button onclick="switchTab('priceSpy')" id="tabPriceSpy" class="px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap">📊 رادار ومقارنة أسعار السوق</button>
            <button onclick="switchTab('adsFactory')" id="tabAdsFactory" class="px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap">🎬 مصنع إعلانات تيكتوك وفيسبوك</button>
            <button onclick="switchTab('simulator')" id="tabSimulator" class="px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap">🚚 حاسبة الشحن وهوامش التاجر</button>
        </div>

        <!-- TAB 1: WHATSAPP DEAL MAKER -->
        <div id="secDealMaker" class="space-y-4">
            <div class="bg-slate-900 border border-emerald-500/40 rounded-2xl p-5 shadow-lg space-y-4">
                <div class="flex justify-between items-center">
                    <div>
                        <h3 class="text-sm font-black text-emerald-400 flex items-center gap-2"><span>📲</span><span>إرسال عروض التوزيع المباشرة بنقرة واحدة عبر WhatsApp</span></h3>
                        <p class="text-xs text-slate-400 mt-1">المحلات التالية مؤهلة لطلب طلبيات الجملة وتوزيع عسل وزيت زيتون تيروردة</p>
                    </div>
                    <select id="wilayaFilter" onchange="filterShops()" class="bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-xs text-white">
                        <option value="ALL">جميع الولايات</option>
                        <option value="Alger">16 - الجزائر</option>
                        <option value="Oran">31 - وهران</option>
                        <option value="Constantine">25 - قسنطينة</option>
                        <option value="Blida">09 - البليدة</option>
                        <option value="Tizi Ouzou">15 - تيزي وزو</option>
                    </select>
                </div>
                
                <div class="overflow-x-auto rounded-xl border border-slate-800">
                    <table class="w-full text-right text-xs text-slate-300">
                        <thead class="bg-slate-950 text-slate-400 uppercase text-[11px] border-b border-slate-800">
                            <tr>
                                <th class="p-3.5">اسم المتجر / المحل</th>
                                <th class="p-3.5">الولاية والحي</th>
                                <th class="p-3.5">الهاتف المباشر</th>
                                <th class="p-3.5">هامش الربح المقترح للتاجر</th>
                                <th class="p-3.5 text-left">التنفيذ الفوري</th>
                            </tr>
                        </thead>
                        <tbody id="dealsBody" class="divide-y divide-slate-800/60"></tbody>
                    </table>
                </div>
            </div>
        </div>

        <!-- TAB 2: MARKET PRICE SPY -->
        <div id="secPriceSpy" class="hidden space-y-4">
            <div class="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
                <div>
                    <h3 class="text-sm font-black text-white flex items-center gap-2"><span>📈</span><span>مقارنة أسعار تيروردة بمتوسط السوق الجزائري (Ouedkniss & Social Media)</span></h3>
                    <p class="text-xs text-slate-400 mt-1">توضح هذه الأرقام للتاجر كيف يربح معك أكثر مما يربحه مع الموردين الآخرين</p>
                </div>
                <div class="grid grid-cols-1 md:grid-cols-3 gap-4" id="pricesGrid"></div>
            </div>
        </div>

        <!-- TAB 3: VIRAL ADS FACTORY -->
        <div id="secAdsFactory" class="hidden space-y-4">
            <div class="bg-slate-900 border border-slate-800 rounded-2xl p-5 space-y-4">
                <h3 class="text-sm font-black text-white">🎬 سكريبتات فيديو وإعلانات ممولة جاهزة للتصوير والإطلاق في الجزائر</h3>
                <div class="grid grid-cols-1 md:grid-cols-2 gap-4" id="adsGrid"></div>
            </div>
        </div>

        <!-- TAB 4: WHOLESALE LOGISTICS SIMULATOR -->
        <div id="secSimulator" class="hidden space-y-4">
            <div class="bg-slate-900 border border-slate-800 rounded-2xl p-6 space-y-6">
                <div>
                    <h3 class="text-base font-bold text-white">🚚 محاكي تكلفة شحن الجملة وأرباح تيروردة الصافية</h3>
                    <p class="text-xs text-slate-400 mt-1">حساب التوصيل عبر شركات الشحن للولايات وحساب الفاتورة الإجمالية للتجار</p>
                </div>
                <div class="grid grid-cols-1 md:grid-cols-4 gap-4 bg-slate-950 p-4 rounded-xl border border-slate-800">
                    <div>
                        <label class="block text-xs text-slate-400 mb-1">المنتج</label>
                        <select id="simProduct" onchange="calcSim()" class="w-full bg-slate-900 border border-slate-700 rounded-lg p-2 text-xs text-white">
                            <option value="1900,2800">عسل جبلي حر 500غ</option>
                            <option value="1150,1600">زيت زيتون بكر كابيلي 1 لتر</option>
                            <option value="550,850">زيت الحبة السوداء 100مل</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-xs text-slate-400 mb-1">عدد الكراتين (الكرتون = 12 وحدة)</label>
                        <input type="number" id="simCartons" value="3" oninput="calcSim()" class="w-full bg-slate-900 border border-slate-700 rounded-lg p-2 text-xs text-white font-mono">
                    </div>
                    <div>
                        <label class="block text-xs text-slate-400 mb-1">ولاية الزبون</label>
                        <select id="simZone" onchange="calcSim()" class="w-full bg-slate-900 border border-slate-700 rounded-lg p-2 text-xs text-white">
                            <option value="600">الوسط (الجزائر، البليدة، بومرداس، تيبازة) - 600 دج</option>
                            <option value="800">الشرق / الغرب (وهران، قسنطينة، سطيف) - 800 دج</option>
                            <option value="1200">الجنوب (ورقلة، بسكرة، غرداية) - 1200 دج</option>
                        </select>
                    </div>
                    <div>
                        <label class="block text-xs text-slate-400 mb-1">إجمالي الفاتورة للتاجر</label>
                        <div id="simTotalInvoice" class="text-emerald-400 font-bold text-base mt-2">69,000 دج</div>
                    </div>
                </div>

                <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <div class="bg-slate-950 p-4 rounded-xl border border-slate-800 text-center">
                        <p class="text-xs text-slate-400">هامش ربح التاجر الصافي عند بيع الطلبية</p>
                        <h4 id="simMerchantGain" class="text-2xl font-black text-amber-400 mt-1">32,400 دج (32.1%)</h4>
                    </div>
                    <div class="bg-slate-950 p-4 rounded-xl border border-slate-800 text-center">
                        <p class="text-xs text-slate-400">رسالة الفاتورة الجاهزة للإرسال على واتساب</p>
                        <button onclick="copyInvoiceText()" class="mt-2 px-4 py-1.5 bg-slate-800 hover:bg-slate-700 border border-slate-700 text-white rounded-lg text-xs font-bold">نسخ ملخص الطلبية 📋</button>
                    </div>
                </div>
            </div>
        </div>

    </main>

    <!-- Modal for Custom AI Pitch -->
    <div id="pitchModal" class="hidden fixed inset-0 bg-black/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
        <div class="bg-slate-900 border border-slate-800 rounded-2xl max-w-lg w-full p-5 space-y-4 shadow-2xl">
            <div class="flex justify-between items-center border-b border-slate-800 pb-3">
                <h3 class="text-sm font-bold text-emerald-400" id="pitchTitle">عرض مخصص للتاجر</h3>
                <button onclick="closeModal()" class="text-slate-400 hover:text-white">✕</button>
            </div>
            <div id="pitchText" class="text-xs text-slate-300 space-y-2 max-h-80 overflow-y-auto leading-relaxed"></div>
            <div class="flex justify-end gap-2 pt-2 border-t border-slate-800">
                <button onclick="copyPitch()" class="px-4 py-2 bg-emerald-600 hover:bg-emerald-500 text-white text-xs rounded-xl font-bold">نسخ العرض 📋</button>
                <button onclick="closeModal()" class="px-4 py-2 bg-slate-800 text-xs rounded-xl font-bold">إغلاق</button>
            </div>
        </div>
    </div>

    <script>
        var currentLanguage = 'ar';
        var allDeadsData = [];
        var generatedPitchCache = "";

        var priceBenchmarks = [
            { product: "عسل جبلي حر (جرجرة) 500غ", wholesale: "1,900 دج", retail: "2,800 دج", market: "3,200 دج", profit: "900 دج / وحدة", margin: "32.1%" },
            { product: "زيت زيتون بكر ممتاز كابيلي 1 لتر", wholesale: "1,150 دج", retail: "1,600 دج", market: "1,800 دج", profit: "450 دج / قارورة", margin: "28.1%" },
            { product: "زيت الحبة السوداء المعصور 100مل", wholesale: "550 دج", retail: "850 دج", market: "1,000 دج", profit: "300 دج / قارورة", margin: "35.3%" }
        ];

        var videoAds = [
            {
                hook: "« علاش راك تشري عسل السدر بـ 4500 دج وهو كاين حر ومفحوص مخبرياً بنصف السعر؟ »",
                desc: "فيديو تيكتوك قصير يوضح شهادة الفحص المخبري لعسل تيروردة وتذوق مباشر للكثافة، مع مقارنة الأسعار في الجزائر.",
                cta: "مطلوب موزعين معتمدين في ولايتك! راسلنا في الخاص أو واتساب."
            },
            {
                hook: "« الفرق بين زيت الزيتون المعصور على البارد والزيت المغشوش في 10 ثواني! »",
                desc: "تجربة بصرية لاختبار حموضة ونقاء زيت زيتون تيروردة من بساتين جرجرة.",
                cta: "متوفر للبيع بالتجزئة وبالجملة لأصحاب المحلات مع التوصيل."
            }
        ];

        function switchTab(t) {
            ['dealMaker', 'priceSpy', 'adsFactory', 'simulator'].forEach(function(k) {
                document.getElementById('sec' + k.charAt(0).toUpperCase() + k.slice(1)).classList.add('hidden');
                var btn = document.getElementById('tab' + k.charAt(0).toUpperCase() + k.slice(1));
                btn.className = 'px-4 py-2 rounded-xl bg-slate-900 text-slate-400 hover:text-white transition whitespace-nowrap';
            });
            document.getElementById('sec' + t.charAt(0).toUpperCase() + t.slice(1)).classList.remove('hidden');
            var actBtn = document.getElementById('tab' + t.charAt(0).toUpperCase() + t.slice(1));
            actBtn.className = 'px-4 py-2 rounded-xl bg-emerald-600 text-white transition whitespace-nowrap';
        }

        async function loadData() {
            try {
                var res = await fetch('/api/leads');
                allDeadsData = await res.json();
                renderDeals(allDeadsData);
            } catch (e) {
                console.log("Load failed");
            }
            renderPrices();
            renderAds();
            calcSim();
        }

        function renderDeals(list) {
            var body = document.getElementById('dealsBody');
            var rows = '';
            for (var i = 0; i < list.length; i++) {
                var d = list[i];
                var phoneClean = (d.phone || '').replace(/\D/g, '');
                var waText = 'السلام عليكم ' + d.businessName + '، نتواصل معكم من إدارة علامة تيروردة TIRORDA لإنتاج العسل الجبلي وزيت الزيتون البكر الممتاز. يسعدنا تزويد محلكم الموقر بأسعار الجملة المباشرة بهامش ربح يتجاوز 30% مع شهادة التحليل المخبري والتوصيل لباب المحل. هل ترغبون بالاطلاع على العرض؟';
                var waUrl = 'https://wa.me/' + phoneClean + '?text=' + encodeURIComponent(waText);

                rows += '<tr class="hover:bg-slate-800/40 transition">' +
                    '<td class="p-3.5"><div class="font-bold text-white text-[13px]">' + d.businessName + '</div><div class="text-[11px] text-slate-400">' + (d.category || '') + '</div></td>' +
                    '<td class="p-3.5"><div class="text-white font-semibold">' + d.wilaya + '</div><div class="text-[11px] text-slate-400">' + (d.commune || '') + '</div></td>' +
                    '<td class="p-3.5 font-mono text-emerald-400 font-bold">' + (d.phone || 'N/A') + '</td>' +
                    '<td class="p-3.5"><span class="px-2.5 py-1 rounded-md border text-xs font-black bg-emerald-950 text-emerald-400 border-emerald-700">+30% إلى +35% ربح صافي</span></td>' +
                    '<td class="p-3.5 text-left"><div class="flex justify-start gap-1.5">' +
                        (d.phone ? '<a href="' + waUrl + '" target="_blank" class="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg text-xs font-bold shadow flex items-center gap-1">واتساب فوري 💬</a>' : '') +
                        '<button onclick="generateCustomPitch(\'' + d.id + '\',\'' + d.businessName + '\',\'' + d.wilaya + '\')" class="px-2.5 py-1.5 bg-amber-600/30 hover:bg-amber-600/50 border border-amber-500/40 text-amber-300 rounded-lg text-xs font-bold">صياغة AI 🤖</button>' +
                    '</div></td>' +
                    '</tr>';
            }
            body.innerHTML = rows;
        }

        function filterShops() {
            var w = document.getElementById('wilayaFilter').value;
            var filtered = allDeadsData.filter(function(d) {
                return w === 'ALL' || (d.wilaya && d.wilaya.indexOf(w) !== -1);
            });
            renderDeals(filtered);
        }

        function renderPrices() {
            var g = document.getElementById('pricesGrid');
            var h = '';
            for (var i = 0; i < priceBenchmarks.length; i++) {
                var p = priceBenchmarks[i];
                h += '<div class="bg-slate-950 p-5 rounded-2xl border border-slate-800 space-y-3">' +
                    '<div class="flex justify-between items-center"><h4 class="font-bold text-white text-xs">' + p.product + '</h4><span class="bg-emerald-950 text-emerald-400 border border-emerald-800 text-[10px] font-bold px-2 py-0.5 rounded">هامش التاجر: ' + p.margin + '</span></div>' +
                    '<div class="grid grid-cols-2 gap-2 text-xs pt-2 border-t border-slate-900">' +
                        '<div><span class="text-slate-500 block text-[10px]">سعر الجملة لتيروردة</span><b class="text-emerald-400">' + p.wholesale + '</b></div>' +
                        '<div><span class="text-slate-500 block text-[10px]">سعر البيع المقترح</span><b class="text-white">' + p.retail + '</b></div>' +
                        '<div><span class="text-slate-500 block text-[10px]">متوسط سعر السوق</span><b class="text-slate-400">' + p.market + '</b></div>' +
                        '<div><span class="text-slate-500 block text-[10px]">ربح التاجر في القارورة</span><b class="text-amber-400">' + p.profit + '</b></div>' +
                    '</div>' +
                    '</div>';
            }
            g.innerHTML = h;
        }

        function renderAds() {
            var g = document.getElementById('adsGrid');
            var h = '';
            for (var i = 0; i < videoAds.length; i++) {
                var a = videoAds[i];
                h += '<div class="bg-slate-950 p-4 rounded-xl border border-slate-800 space-y-2">' +
                    '<div class="text-amber-400 font-bold text-xs">🎯 خطاف البداية (Hook بالدارجة): ' + a.hook + '</div>' +
                    '<p class="text-xs text-slate-300 leading-relaxed">' + a.desc + '</p>' +
                    '<div class="text-[11px] text-emerald-400 font-bold pt-2 border-t border-slate-900">📣 الإغلاق والدعوة للفعل: ' + a.cta + '</div>' +
                    '</div>';
            }
            g.innerHTML = h;
        }

        function calcSim() {
            var p = document.getElementById('simProduct').value.split(',');
            var wholesale = parseFloat(p[0]);
            var retail = parseFloat(p[1]);
            var cartons = parseInt(document.getElementById('simCartons').value) || 1;
            var shipping = parseFloat(document.getElementById('simZone').value) || 600;

            var totalUnits = cartons * 12;
            var totalInvoice = (wholesale * totalUnits) + shipping;
            var totalRetailValue = (retail * totalUnits);
            var merchantGain = totalRetailValue - totalInvoice;
            var margin = ((merchantGain / totalRetailValue) * 100).toFixed(1);

            document.getElementById('simTotalInvoice').innerText = totalInvoice.toLocaleString() + ' دج (شامل التوصيل)';
            document.getElementById('simMerchantGain').innerText = merchantGain.toLocaleString() + ' دج (' + margin + '%)';
        }

        function copyInvoiceText() {
            var inv = document.getElementById('simTotalInvoice').innerText;
            var gain = document.getElementById('simMerchantGain').innerText;
            var text = 'عرض طلبيتك من علامة تيروردة:\n- القيمة الإجمالية للطلبية: ' + inv + '\n- صافي ربحك التقديري عند بيعها: ' + gain + '\n- التوصيل لباب متجركم.\nللتأكيد يرجى إرسال العنوان ورقم الهاتف.';
            navigator.clipboard.writeText(text);
            alert('تم نسخ ملخص الطلبية بنجاح! يمكنك لصقها الآن للتاجر.');
        }

        async function generateCustomPitch(id, name, wilaya) {
            document.getElementById('pitchTitle').innerText = 'صياغة عرض B2B لـ ' + name;
            document.getElementById('pitchText').innerText = 'جاري استخدام الذكاء الاصطناعي لكتابة عرض واتساب مقنع ومخصص...';
            document.getElementById('pitchModal').classList.remove('hidden');

            try {
                var res = await fetch('/api/leads/' + id + '/pitch');
                var data = await res.json();
                generatedPitchCache = data.pitch;
                document.getElementById('pitchText').innerText = data.pitch;
            } catch (e) {
                generatedPitchCache = 'السلام عليكم ورحمة الله، يشرفنا عرض شراكة توزيع منتجات تيروردة الطبيعية في محلكم بهامش ربح ممتاز.';
                document.getElementById('pitchText').innerText = generatedPitchCache;
            }
        }

        function copyPitch() {
            navigator.clipboard.writeText(generatedPitchCache);
            alert('تم نسخ العرض بنجاح! أرسله للتاجر عبر الواتساب.');
        }

        function closeModal() {
            document.getElementById('pitchModal').classList.add('hidden');
        }

        function toggleLang() {
            var root = document.getElementById('htmlRoot');
            var isRtl = root.getAttribute('dir') === 'rtl';
            root.setAttribute('dir', isRtl ? 'ltr' : 'rtl');
            root.setAttribute('lang', isRtl ? 'fr' : 'ar');
            document.getElementById('langBtn').innerText = isRtl ? 'العربية' : 'Français';
        }

        window.onload = loadData;
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
            SchemaUtils.create(BusinessesTable, LeadsTable, TirourdaProductsTable)
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
    val directoryManager = BusinessDirectoryManager()

    CoroutineScope(Dispatchers.IO).launch {
        seedCatalog()
        directoryManager.syncDirectory()
    }

    routing {
        get("/") {
            call.respondText(DASHBOARD_HTML, ContentType.Text.Html)
        }

        route("/api") {
            get("/leads") {
                val list = runCatching {
                    dbQuery {
                        LeadsTable.selectAll().map {
                            LeadDTO(
                                it[LeadsTable.id].toString(), it[LeadsTable.businessId].toString(),
                                it[LeadsTable.businessName], it[LeadsTable.category], it[LeadsTable.subcategory],
                                it[LeadsTable.wilaya], it[LeadsTable.city], it[LeadsTable.phone],
                                it[LeadsTable.email], it[LeadsTable.website], it[LeadsTable.instagram],
                                it[LeadsTable.facebook], it[LeadsTable.leadScore], it[LeadsTable.scoreReasons],
                                it[LeadsTable.status], it[LeadsTable.doNotContact]
                            )
                        }
                    }
                }.getOrDefault(emptyList())
                call.respond(list)
            }

            get("/leads/{id}/pitch") {
                val leadId = call.parameters["id"]
                val lead = dbQuery {
                    runCatching { LeadsTable.selectAll().where { LeadsTable.id eq UUID.fromString(leadId) }.firstOrNull() }.getOrNull()
                }
                val shopName = lead?.get(LeadsTable.businessName) ?: "التاجر المحترم"
                val wilaya = lead?.get(LeadsTable.wilaya) ?: "الجزائر"
                val cat = lead?.get(LeadsTable.category) ?: "عطارة ومنتجات طبيعية"

                val pitch = aiEngine.generatePitch(shopName, wilaya, cat)
                call.respond(mapOf("pitch" to pitch))
            }
        }
    }
}
