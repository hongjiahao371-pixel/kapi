package kapi.core.auto

/** Import-only taxonomy hints. A platform's broad category cannot overrule purchase evidence. */
object ImportCategoryHints {
    private val groups=listOf(
        listOf("餐饮","餐饮美食","餐饮食品","食品餐饮","饮食","餐饮支出"),
        listOf("交通","交通出行","行车交通"), listOf("居住","住房","居家物业"),
        listOf("娱乐","休闲娱乐","文化休闲"), listOf("医疗","医疗健康","医疗保健"),
        listOf("日用","生活日用","日常用品"), listOf("通讯","通信","通讯网络"),
        listOf("教育","学习教育","学习办公"), listOf("其他","其他支出"),
        listOf("正餐","快餐","餐费"), listOf("数码","数码电器","电子数码"),
        listOf("过路费","通行费","高速费"), listOf("停车","停车费"),
        listOf("咖啡茶饮","饮品","茶饮"), listOf("软件服务","软件订阅"),
        listOf("工资","工资薪资","薪资"), listOf("奖金","奖金收入"))
    fun aliases(name:String):Set<String> = groups.firstOrNull {name.trim() in it}?.toSet() ?: setOf(name.trim())
    fun placeholder(path:String?)=path==null || path.trim() in setOf("","/","-","未分类","无分类","无","其他","其它","其他支出")
    fun path(category:String?, income:Boolean):CategoryRules.Path? {
        if(placeholder(category)) return null
        val parts=category!!.split('/').map(String::trim)
        // Only explicit platform labels, not arbitrary merchant-like strings, become broad hints.
        val major=groups.take(9).firstOrNull {parts.first() in it}?.first()
        if(!income) {
            val explicit=when(category.trim()) {
                "数码电器", "电子数码" -> CategoryRules.Path("购物","数码")
                "服饰装扮", "服饰鞋包" -> CategoryRules.Path("购物","服饰")
                "家居家装" -> CategoryRules.Path("购物","家居")
                "日用百货" -> CategoryRules.Path("日用")
                "充值缴费", "通讯话费" -> CategoryRules.Path("通讯","话费")
                "医疗保健" -> CategoryRules.Path("医疗")
                else -> null
            }
            if(explicit!=null) return explicit
            if(major!=null) return CategoryRules.Path(major)
        }
        if(income && parts.first() in setOf("工资","工资薪资","薪资")) return CategoryRules.Path("工资")
        return null
    }
}
