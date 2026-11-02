"use strict";
(() => {
  const tools=document.getElementById("view-tools");if(!tools)return;
  const card=document.createElement("div");card.className="tool-card card";card.style.gridColumn="1 / -1";
  const heading=document.createElement("h2");heading.textContent="归账 Pro";
  const status=document.createElement("p");status.setAttribute("role","status");
  const price=document.createElement("p");price.style.cssText="font-size:24px;font-weight:650;color:var(--brand);margin:12px 0";
  const features=document.createElement("div");features.style.cssText="display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:14px;margin:16px 0";
  const purchase=document.createElement("button");purchase.type="button";purchase.className="btn";purchase.textContent="购买永久 Pro · 商户接入准备中";purchase.disabled=true;
  const description=document.createElement("p");description.textContent="基础记账、分类明细、导入修正与去重、完整导出、备份恢复、HTTPS 和 NAS 同步免费。Pro 可创建家庭账本，受邀成员不重复购买；已有家庭继续可用。";
  const input=document.createElement("textarea");input.placeholder="已有激活码";input.setAttribute("aria-label","Pro 激活码");input.maxLength=4096;input.autocomplete="off";input.rows=3;input.style.cssText="display:block;width:100%;box-sizing:border-box;resize:vertical;min-height:86px;padding:13px 15px;margin:14px 0;border:1px solid var(--line);border-radius:14px;background:var(--fill);color:var(--ink);font:inherit;font-size:13px;overflow-wrap:anywhere";
  const button=document.createElement("button");button.className="btn primary";button.textContent="激活 / 恢复已有权益";
  const orderBox=document.createElement("div");orderBox.hidden=true;
  const orderTitle=document.createElement("h3");orderTitle.textContent="从已付款订单恢复";
  const recovery=document.createElement("textarea");recovery.placeholder="安卓购买凭据（GZB1 开头）";recovery.setAttribute("aria-label","安卓购买凭据");recovery.maxLength=2048;recovery.autocomplete="off";
  const orderID=document.createElement("input");orderID.placeholder="订单编号";orderID.setAttribute("aria-label","购买订单编号");orderID.maxLength=32;
  const orderToken=document.createElement("input");orderToken.type="password";orderToken.placeholder="购买凭据";orderToken.setAttribute("aria-label","订单购买凭据");orderToken.autocomplete="off";orderToken.maxLength=128;
  const restoreOrder=document.createElement("button");restoreOrder.className="btn";restoreOrder.textContent="核对订单并恢复 Pro";
  const orderHint=document.createElement("p");orderHint.textContent="购买凭据只用于核对该笔订单，请私下保存。账单导出文件不充当购买凭据。";
  orderBox.append(orderTitle,recovery,orderID,orderToken,restoreOrder,orderHint);
  restoreOrder.addEventListener("click",async()=>{restoreOrder.disabled=true;try{render(await request("/v1/panel/pro/order/activate",recovery.value.trim()?{recovery_code:recovery.value.trim()}:{order_id:orderID.value.trim(),order_token:orderToken.value.trim()}));orderID.value="";orderToken.value="";recovery.value="";}catch(e){status.textContent=e.message;}finally{restoreOrder.disabled=false;}});
  async function request(path,body){const response=await fetch(API_BASE+path,{method:body?"POST":"GET",headers:{"X-Panel-Token":S.token,"Content-Type":"application/json"},body:body?JSON.stringify(body):undefined,cache:"no-store"});const data=await response.json();if(!response.ok)throw new Error(data.message||"无法读取权益");return data;}
  function render(value){
    status.textContent=value.source+" · 支付暂未开放";
    orderBox.hidden=!value.order_restore_available;
    const catalog=value.catalogue;if(!catalog)return;
    price.textContent=catalog.product.price_label+" · 永久 Pro";
    features.replaceChildren();
    for(const [label,ids] of [["免费功能",Object.keys(value.free)],["Docker 本端 Pro",catalog.platforms.nas]]){
      const group=document.createElement("div"),title=document.createElement("h3"),list=document.createElement("ul");title.textContent=label;
      for(const id of ids){const row=document.createElement("li");row.textContent=value.free[id]||value.pro[id];row.style.marginBottom="8px";list.append(row);}
      group.append(title,list);features.append(group);
    }
    annual.disabled=!value.enabled;annualHint.textContent=value.enabled?"按账单页当前账本生成十二个月的收支报告，退款与账户划转沿用现有统计口径。":"年度报告需要 Pro；基础统计、分类明细与完整账单导出免费。";
  }
  async function load(){if(!S.token)return;try{render(await request("/v1/panel/pro"));}catch(e){status.textContent=e.message;}}
  button.addEventListener("click",async()=>{button.disabled=true;try{const value=await request("/v1/panel/pro/activate",{code:input.value.trim()});input.value="";render(value);if(window.FamilyPanel)await window.FamilyPanel.load();}catch(e){status.textContent=e.message;}finally{button.disabled=false;}});
  const annualCard=document.createElement("div");annualCard.className="tool-card card";annualCard.style.gridColumn="1 / -1";
  const annualTitle=document.createElement("h2");annualTitle.textContent="年度报告 · Pro";
  const year=document.createElement("input");year.type="number";year.min="2000";year.max="2100";year.value=String(new Date().getFullYear());year.setAttribute("aria-label","年度报告年份");year.style.cssText="padding:10px;border:1px solid var(--line);border-radius:12px;background:var(--fill);color:var(--ink);margin-right:12px;width:100px";
  const annual=document.createElement("button");annual.className="btn primary";annual.textContent="生成年度报告";annual.disabled=true;
  const annualHint=document.createElement("p");annualHint.setAttribute("role","status");
  const report=document.createElement("div");report.style.overflowX="auto";
  annual.addEventListener("click",async()=>{
    annual.disabled=true;try{
      const params=new URLSearchParams({year:year.value,ledger_id:S.filter.ledger_id||""});
      const data=await request("/v1/panel/reports/annual?"+params);report.replaceChildren();
      const table=document.createElement("table"),head=document.createElement("tr");
      for(const name of ["月份","收入","净支出","结余"]){const c=document.createElement("th");c.textContent=name;head.append(c);}table.append(head);
      const rows=[["月份","收入","净支出","结余"]];
      data.months.forEach((month,i)=>{const cells=[String(i+1)+"月",...['income','expense','balance'].map(k=>(Number(month[k])/100).toFixed(2))],row=document.createElement("tr");for(const value of cells){const c=document.createElement("td");c.textContent=value;row.append(c);}table.append(row);rows.push(cells);});
      report.append(table);annualHint.textContent=data.year+" 年 · 收入 "+(data.income/100).toFixed(2)+" · 净支出 "+(data.expense/100).toFixed(2)+" · 结余 "+(data.balance/100).toFixed(2);
      const download=document.createElement("button");download.className="btn";download.textContent="保存年度报告 CSV";download.addEventListener("click",()=>{const content="\ufeff"+rows.map(row=>row.map(v=>'"'+String(v).replaceAll('"','""')+'"').join(',')).join('\r\n');const url=URL.createObjectURL(new Blob([content],{type:"text/csv;charset=utf-8"})),a=document.createElement("a");a.href=url;a.download="归账年度报告-"+data.year+".csv";a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});report.append(download);
    }catch(e){annualHint.textContent=e.message;}finally{annual.disabled=false;}
  });
  card.append(heading,status,price,description,features,purchase,input,button,orderBox);tools.append(card);
  annualCard.append(annualTitle,year,annual,annualHint,report);tools.append(annualCard);
  window.ProPanel={load};
})();
