package kapi.core.investment

import java.math.BigDecimal
import java.math.RoundingMode

/** Conservative field extraction from OCR rows. Missing data remains missing; never fetch a NAV to invent cost. */
object HoldingScreenshotParser {
    data class Line(val text:String, val left:Int, val top:Int, val right:Int, val bottom:Int) {
        val centerY:Int get()=(top+bottom)/2
        val height:Int get()=(bottom-top).coerceAtLeast(1)
    }
    data class Candidate(
        val name:String, val code:String?, val quantity:String?, val costMinor:Long?,
        val costBasis:String, val marketValueMinor:Long?, val warnings:List<String>,
        val holdingProfitMinor:Long?=null,
    ) {
        val averageCost:String? get() = average(costMinor, quantity)
    }
    private val number = Regex("""[+-]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,8})?(?:万)?""")
    private val fundName = Regex("基金|混合|债券|指数|股票|ETF|联接|QDII|货币", RegexOption.IGNORE_CASE)
    private val fields = listOf("持有份额","持仓份额","基金份额","确认份额","持有份数","份额","份数","成本单价","成本净值","平均成本价","持仓均价","持仓成本","持有成本","成本金额","持仓本金","持有金额","持仓金额","参考市值","持仓市值","当前市值","浮动盈亏","持仓盈亏","持有收益","累计收益","收益率","单位净值","最新净值")
    private fun nameLine(s:String):Boolean = (s.startsWith("基金名称") || (fundName.containsMatchIn(s) && s.length > 3)) &&
        fields.none { s.startsWith(it) } && !s.startsWith("基金代码") && s !in setOf("我的基金","基金持仓","基金详情","持仓详情","基金资产","全部基金","基金收益","持有基金") &&
        !s.contains("元") && !s.contains("份额")

    /** A labelled list uses geometry: flattening its columns attaches yesterday's return to a name. */
    fun parse(text:String, lines:List<Line>):List<Candidate> {
        require(text.length<=100_000 && lines.size<=3000) { "截图文字过多，请截取持仓区域" }
        fun normalized(s:String)=java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC)
            .replace('−','-').replace('﹣','-').replace('–','-').replace('—','-').replace('額','额')
        fun compact(s:String)=normalized(s).replace(Regex("\\s+"),"")
        val amount=lines.firstOrNull { compact(it.text).let {s -> s.startsWith("金") || s.startsWith("|金") } && compact(it.text).contains("/昨日收益") } ?: return parse(text)
        val profit=lines.firstOrNull { compact(it.text).contains("持有收益/率") && kotlin.math.abs(it.centerY-amount.centerY)<=maxOf(it.height,amount.height) }
            ?: return emptyList()
        if(profit.left<=amount.right || amount.left<=0) return emptyList()
        val numeric=Regex("[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?")
        val values=lines.filter { it.top>maxOf(amount.bottom,profit.bottom) && it.left>=amount.left && it.right<profit.left &&
            numeric.matches(normalized(it.text.trim())) && !normalized(it.text.trim()).startsWith("-") && !normalized(it.text.trim()).startsWith("+") }
            .sortedBy { it.centerY }
        val candidates=values.mapNotNull { value ->
            val names=lines.filter { it.right<amount.left && kotlin.math.abs(it.centerY-value.centerY)<=value.height*3 &&
                it.top>=value.top-value.height && it.text.any { ch -> ch in '\u4e00'..'\u9fff' } }
                .sortedBy { it.top }
            if(names.isEmpty() || kotlin.math.abs(names.first().centerY-value.centerY)>maxOf(names.first().height,value.height)) return@mapNotNull null
            val name=names.joinToString("") { it.text.trim() }.trimStart('|')
            if(name.length<4 || name.contains("市场解读") || name.contains("基金销售")) return@mapNotNull null
            val gains=lines.filter { it.left>=profit.left && numeric.matches(normalized(it.text.trim())) &&
                kotlin.math.abs(it.centerY-value.centerY)<=maxOf(it.height,value.height)/2 }
            val gain=gains.singleOrNull()?.text?.trim()?.let(::normalized)?.replace(",","")
            val p=parseBlock(listOf("基金名称：$name","持仓市值 ${normalized(value.text.trim())}") +
                (gain?.let { listOf("持有收益 $it") } ?: emptyList()))
            val warnings=p.warnings + listOf("列表截图缺少代码和份额，请打开基金持仓详情补齐；名称需核对") +
                (if(Regex("\\.{2,}|…").containsMatchIn(name)) listOf("基金名称被截断，请填写完整名称") else emptyList())
            p.copy(warnings=warnings.distinct(), holdingProfitMinor=gain?.let { signedMinor(it) })
        }
        require(candidates.size<=20) { "一张截图最多识别 20 只基金，请分批选择" }
        return candidates
    }

    private fun signedMinor(raw:String):Long?=runCatching {
        BigDecimal(raw).movePointRight(2).longValueExact().also { require(it in -99_999_999_999L..99_999_999_999L) }
    }.getOrNull()

    fun parse(text:String):List<Candidate> {
        require(text.length <= 100_000) { "截图文字过多，请截取持仓区域" }
        // Bundled Chinese OCR can emit traditional glyphs even for a simplified UI.
        val glyphs=mapOf('額' to '额','倉' to '仓','價' to '价','淨' to '净','虧' to '亏','數' to '数','幣' to '币','碼' to '码','單' to '单','當' to '当','總' to '总','編' to '编','號' to '号','債' to '债','貨' to '货','聯' to '联')
        val normalized=text.map { glyphs[it] ?: it }.joinToString("")
        val lines = java.text.Normalizer.normalize(normalized,java.text.Normalizer.Form.NFKC).replace('，',',').replace('：',':').replace('−','-').replace('﹣','-').replace('–','-').replace('—','-')
            .map { if(it in '０'..'９') ('0'.code + it.code-'０'.code).toChar() else it }.joinToString("")
            .lines().map { it.trim() }.filter { it.isNotBlank() }
        require(lines.none { Regex("""(?:币种|计价币种|结算币种)\s*:?\s*(?:USD|HKD|EUR|美元|港元|欧元)|(?:USD|HKD|EUR|[$])\s*\d|\d\s*(?:美元|港元|欧元)""",RegexOption.IGNORE_CASE).containsMatchIn(it) }) { "首版仅支持人民币基金，请勿导入外币持仓截图" }
        val nameStarts = lines.indices.filter { nameLine(lines[it]) }
        require(nameStarts.size<=20) { "一张截图最多识别 20 只基金，请分批选择" }
        val starts = if(nameStarts.isNotEmpty()) nameStarts else listOf(0)
        return starts.take(20).mapIndexed { i,start ->
            val end=starts.getOrNull(i+1) ?: lines.size
            parseBlock(lines.subList(start,end))
        }.filter { it.name.isNotBlank() || it.code!=null || it.quantity!=null }
    }

    private fun parseBlock(lines:List<String>):Candidate {
        val nameRaw=lines.firstOrNull { nameLine(it) }.orEmpty()
        val explicitCode=Regex("""(?:基金代码|基金编号|代码)\s*:?\s*(\d{6})(?!\d)""").find(lines.joinToString("\n"))?.groupValues?.get(1)
        val inlineCode=Regex("""(?:^|[\s(（])(\d{6})(?:$|[\s)）])""").find(nameRaw)?.groupValues?.get(1)
        val standalone=lines.indices.firstOrNull { idx -> lines[idx].matches(Regex("\\d{6}")) && idx>0 && nameLine(lines[idx-1]) }?.let { lines[it] }
        val code=explicitCode ?: inlineCode ?: standalone
        val name=nameRaw.removePrefix("基金名称").trim().trimStart(':','：').trim()
            .replace(Regex("""[（(]?\s*\d{6}\s*[)）]?"""),"").trim()
        val warnings=mutableListOf<String>()
        fun read(labels:List<String>):BigDecimal? {
            val values=mutableListOf<BigDecimal>()
            for((index,line) in lines.withIndex()) {
                val label=labels.sortedByDescending { it.length }.firstOrNull { line.startsWith(it) } ?: continue
                val tail=line.removePrefix(label).trim().replace(Regex("^[(（](?:元|份|股|元/份)[)）]"),"").trim().trimStart(':','：').trim().trimStart('¥','￥').trim()
                // Only a single value or the next isolated numeric row can be linked to a label.
                val raw=if(tail.isNotEmpty()) tail else lines.getOrNull(index+1).orEmpty().trim().trimStart('¥','￥').trim()
                val match=number.find(raw) ?: continue
                if(match.range.first!=0 || raw.substring(match.range.last+1).trim() !in setOf("","元","份","股","份/股","元/份")) continue
                val token=match.value
                val n=BigDecimal(token.removeSuffix("万").replace(",",""))
                    .multiply(if(token.endsWith("万")) BigDecimal(10000) else BigDecimal.ONE)
                values+=n
            }
            if(values.distinct().size>1) { warnings+="${labels.first()}出现不同数值，请核对";return null }
            return values.firstOrNull()
        }
        val units=read(listOf("持有份额","持仓份额","基金份额","确认份额","持有份数","持仓份数","份额","份数"))
            ?.takeIf { it>BigDecimal.ZERO && it<=BigDecimal("99999999999") }
            ?.stripTrailingZeros()?.toPlainString()?.takeIf { runCatching { Portfolio.quantity(it) }.isSuccess }
        fun minor(n:BigDecimal?):Long? = runCatching {
            require(n!=null && n>=BigDecimal.ZERO)
            n.movePointRight(2).setScale(0,RoundingMode.HALF_UP).longValueExact().also { require(it<=99_999_999_999L) }
        }.getOrNull()
        val explicit=minor(read(listOf("持仓成本","持有成本","成本金额","持仓本金")))
        val unitCost=read(listOf("成本单价","成本净值","平均成本价","持仓均价"))?.takeIf {it>=BigDecimal.ZERO}
        val fromUnit=if(units!=null && unitCost!=null) minor(unitCost.multiply(BigDecimal(units))) else null
        val value=minor(read(listOf("持有金额","持仓金额","参考市值","持仓市值","当前市值")))
        // Cumulative returns, today's returns and percentages are not equivalent to open-position profit.
        val profit=read(listOf("浮动盈亏","持仓盈亏","持有收益"))
        val inferred=if(value!=null && profit!=null) minor(BigDecimal(value).movePointLeft(2).subtract(profit)) else null
        var cost=explicit ?: fromUnit ?: inferred
        var basis=when {explicit!=null -> "截图持仓成本";fromUnit!=null -> "截图成本单价 × 份额（按分四舍五入）";inferred!=null -> "估算：持仓市值 − 持仓收益";else -> "成本信息不足，请手动填写"}
        if(warnings.any { it.contains("出现不同数值") && it.contains("成本") }) { cost=null;basis="成本字段冲突，请核对后填写" }
        if(explicit!=null && fromUnit!=null && kotlin.math.abs(explicit-fromUnit)>maxOf(2L,(explicit/10000))) {
            cost=null;basis="成本字段不一致，请核对后填写";warnings+="总成本与成本单价不一致，可能存在 OCR 错误或净值舍入"
        }
        if(explicit==null && fromUnit==null && inferred!=null) warnings+="仅在该收益表示当前持仓的浮动盈亏时成立；若含已卖出收益或分红，请手动改成本"
        if(code==null) warnings+="未识别到六位代码，请补齐";
        if(units==null) warnings+="未识别到有效份额，请补齐；金额不能当作份额"
        if(cost==null) warnings+="不能用最新净值当作购入成本"
        return Candidate(name,code,units,cost,basis,value,warnings.distinct())
    }
    fun average(costMinor:Long?,quantity:String?):String? = runCatching {
        require(costMinor!=null && costMinor>=0 && quantity!=null)
        val q=Portfolio.quantity(quantity);require(q>BigDecimal.ZERO)
        BigDecimal(costMinor).movePointLeft(2).divide(q,8,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }.getOrNull()
}
