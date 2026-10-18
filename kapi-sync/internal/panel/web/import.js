"use strict";
window.NasImport=(()=>{
 let preview=null,selected=new Set(),choices={},page=0,busy=false,fileName="",repairRow=0,repairOriginal={},categoryGroups=[];
 const fieldNames={TIME:"时间",CLOCK:"时分秒",AMOUNT:"金额",DIRECTION:"收支",INCOME:"收入金额",EXPENSE:"支出金额",CURRENCY:"币种",NATURE:"交易性质",ACCOUNT:"转出账户",TO_ACCOUNT:"转入账户"};
 const categoryName=id=>preview?.catalog.categories.find(c=>c.id===id)?.path||preview?.catalog.categories.find(c=>c.id===id)?.name||"未分类";
 function setPreview(result){preview=result;selected=new Set(result.candidates.map((c,i)=>c.match==="NEW"?i:null).filter(i=>i!==null));choices={};page=0;render();}
 const status=(text)=>{$("#import-status").textContent=text;};
 function phase(n){for(const [i,id] of ["file","review","sync"].entries())$("#import-step-"+id).classList.toggle("active",i+1===n);}
 function reset(){if(preview&&S.token)post("/v1/panel/import/cancel",{ticket:preview.ticket}).catch(()=>{});preview=null;selected.clear();choices={};page=0;$("#import-review").classList.add("hide");$("#import-result").classList.add("hide");$("#import-upload-card").classList.remove("hide");$("#import-file").value="";status("");phase(1);}
 async function loadCatalog(){
  const ledger=$("#import-ledger").value;
  const data=await api("/v1/panel/import/catalog",{ledger_id:ledger});
  $("#import-ledger").innerHTML='<option value="">请选择账本</option>'+(data.catalog.ledgers||[]).map(x=>'<option value="'+esc(x.id)+'">'+esc(x.name)+'</option>').join("");
  $("#import-ledger").value=ledger||(data.catalog.ledgers.length===1?data.catalog.ledgers[0].id:"");
  if(!preview)status("");
  $("#import-preview").disabled=!data.available;
  if(!data.available)status("NAS 需要更新完整 Docker 镜像，才能读取账单文件。");
  else if(!data.catalog.ledgers.length)status("还没有同步的账本。请先在手机连接 NAS 并完成一次同步。");
 }
 async function post(path,body){
  const token=S.token,headers={"X-Panel-Token":token};if(!(body instanceof FormData)){headers["Content-Type"]="application/json";body=JSON.stringify(body);}
  const r=await fetch(apiPath(path),{method:"POST",headers,body,cache:"no-store"});let d;try{d=await r.json();}catch{throw new Error("NAS 返回异常，请检查连接后重试。");}
  if(r.status===401){signOut();reset();throw new Error("面板令牌已失效，请重新登录。");}
  if(!r.ok)throw new Error(d.message||"账单读取失败");return d;
 }
 function accountOptions(keep){return '<option value="">请核对账户</option>'+preview.catalog.accounts.map(a=>'<option value="'+esc(a.id)+'"'+(a.id===keep?' selected':'')+'>'+esc(a.name)+'</option>').join("");}
 function rows(){
  if(!preview)return;const start=page*100,end=Math.min(start+100,preview.candidates.length);
  $("#import-rows").innerHTML=preview.candidates.slice(start,end).map((c,local)=>{
   const i=start+local,r=c.row,choice=choices[i]||{},transfer=r.toAccount!=null;
   const account=choice.accountId||c.accountId,accountName=preview.catalog.accounts.find(a=>a.id===account)?.name;
   const categories='<select aria-label="第 '+(i+1)+' 笔分类" data-category="'+i+'"'+(transfer||['DUPLICATE','DELETED'].includes(c.match)?' disabled':'')+'><option value="">'+esc(transfer ? '划转不计收支' : c.categoryId ? '本地匹配 · '+categoryName(c.categoryId) : '尚未匹配')+'</option>'+preview.catalog.categories.filter(x=>x.kind===r.direction).map(x=>'<option value="'+esc(x.id)+'"'+(choice.categoryId===x.id?' selected':'')+'>'+esc(x.path||x.name)+'</option>').join('')+'</select>';
   let a=accountName?esc(accountName):'<select aria-label="第 '+(i+1)+' 笔转出账户" data-account="'+i+'">'+accountOptions(account)+'</select>';
   if(transfer){const to=choice.toAccountId||c.toAccountId;a+='<div class="import-detail">划转至</div><select aria-label="第 '+(i+1)+' 笔转入账户" data-to="'+i+'">'+accountOptions(to)+'</select>';}
   if(!accountName && c.accountHints?.length)a+='<div class="import-detail">候选：'+esc(c.accountHints.join('、'))+'（请核对选择）</div>';
   const match=c.match==="DELETED"?"已删除，可勾选恢复":c.match==="DUPLICATE"?"账单已存在":c.match==="SUSPECTED"?"疑似重复，请核对":"新账单";
   const noteFirst=(r.note||"").split("\n")[0],title=r.merchant||(noteFirst&&!noteFirst.startsWith("账本：")&&!noteFirst.startsWith("所属账本：")?noteFirst:r.category)||"账单";
   return '<tr><td><input type="checkbox" aria-label="选择第 '+(i+1)+' 笔账单" data-select="'+i+'"'+(selected.has(i)?' checked':'')+(c.match==="DUPLICATE"?' disabled':'')+'></td><td>'+esc(title)+'<div class="import-detail">'+esc('原分类：'+(r.category||'未提供'))+'</div>'+categories+'<div class="import-detail">'+esc(r.note||"")+'</div></td><td>'+esc(stamp(r.occurredAt)+":"+p2(new Date(r.occurredAt).getSeconds()))+'</td><td>'+a+'</td><td class="num '+(r.direction==="INCOME"?"amt-inc":"amt-exp")+'">'+signed(r.amountMinor,transfer?"TRANSFER":r.direction)+'</td><td>'+esc(match)+'<div class="import-detail">'+esc(c.reason)+(c.existingDeleted?' · 已有账单已删除':'')+'</div>'+(c.existingRow?'<div class="import-existing">已有：'+esc(c.existingRow.merchant||'未提供商户')+' · '+esc(money(c.existingRow.amountMinor))+'<br>'+esc(stamp(c.existingRow.occurredAt))+' · '+esc(c.existingRow.account||'未指定账户')+(c.existingCount>1?'<br>共有 '+c.existingCount+' 条相近记录':'')+'</div>':'')+(c.existingId?'<button class="linkbtn" data-existing="'+i+'">查看已有账单</button>':'')+'</td></tr>';
  }).join("");
  $("#import-page").textContent=preview.candidates.length?'第 '+(start+1)+'—'+end+' 笔，共 '+preview.candidates.length+' 笔':'没有可导入的账单';
  $("#import-prev").disabled=page===0;$("#import-next").disabled=end>=preview.candidates.length;
  for(const el of document.querySelectorAll("[data-select]"))el.addEventListener("change",()=>{const i=Number(el.dataset.select);if(el.checked)selected.add(i);else selected.delete(i);update();});
  for(const el of document.querySelectorAll("[data-account],[data-to]"))el.addEventListener("change",()=>{const i=Number(el.dataset.account??el.dataset.to);choices[i]||={};choices[i][el.dataset.to!=null?"toAccountId":"accountId"]=el.value;update();});
  for(const el of document.querySelectorAll("[data-category]"))el.addEventListener("change",()=>{const i=Number(el.dataset.category);choices[i]||={};choices[i].categoryId=el.value;update();});
  for(const el of document.querySelectorAll("[data-existing]"))el.addEventListener("click",()=>run(()=>openRow(preview.candidates[Number(el.dataset.existing)].existingId)));
  update();
 }
 function update(){
  if(!preview)return;let income=0,expense=0,ready=selected.size>0;
  for(const i of selected){const c=preview.candidates[i],r=c.row,o=choices[i]||{},a=o.accountId||c.accountId||$("#import-account").value,to=o.toAccountId||c.toAccountId;
   if(c.match!=="DELETED"&&(!a||r.toAccount!=null&&(!to||to===a)))ready=false;
   if(c.match!=="DELETED"&&r.category&&!(o.categoryId||c.categoryId)&&!$("#import-preserve").checked&&!$("#import-unclassified").checked)ready=false;
   const actual=c.match==="DELETED"&&c.existingRow?c.existingRow:r;
   if(actual.toAccount==null){if(actual.direction==="INCOME")income+=actual.amountMinor;else expense+=actual.amountMinor;}
  }
  if(preview.parsed.issueCount>0&&!$("#import-ack")?.checked)ready=false;
  $("#import-selected").textContent='已选 '+selected.size+' 笔 · 支出 '+money(expense)+' · 收入 '+money(income);
  $("#import-commit").disabled=busy||!ready;$("#import-commit").textContent=busy?'正在处理…':'确认导入 '+selected.size+' 笔并同步';
 }
 function render(){
  const p=preview.parsed,cs=preview.candidates;
  $("#import-source").textContent=p.sourceName+' · 核对导入';$("#import-filename").textContent=fileName;
  $("#import-filename").textContent=fileName+(p.profile?" · "+p.profile:"");
  $("#import-summary").innerHTML=[['原文件',(p.sourceRecords??cs.length)+' 条'],['手工调整',(p.correctedRows||0)+' 行'],['识别支出',money(p.expenseMinor)],['识别收入',money(p.incomeMinor)],['识别账单',cs.length+' 笔'],['疑似重复',cs.filter(c=>c.match==="SUSPECTED").length+' 笔'],['账单已存在',cs.filter(c=>c.match==="DUPLICATE").length+' 笔'],['已删除可恢复',cs.filter(c=>c.match==="DELETED").length+' 笔']].map(([label,value])=>'<span>'+esc(label)+'<strong class="num">'+esc(value)+'</strong></span>').join("");
  const issues=(p.issues||[]).map(x=>'<li>'+esc((x.row?'数据第 '+x.row+' 行：':'')+x.reason)+(x.row>0?' <button class="btn ghost tiny" data-repair="'+x.row+'">修正第 '+x.row+' 行</button>':'')+'</li>').join("");
  $("#import-issues").innerHTML=(p.checks||[]).map(x=>'<p class="import-help">'+esc(x)+'</p>').join("")+(p.issueCount?'<details class="import-warning" open><summary>'+p.issueCount+' 项不会导入，请先核对</summary><ul>'+issues+'</ul>'+(p.issueCount>p.issues.length?'<p>另有 '+(p.issueCount-p.issues.length)+' 项未列出，请保留原文件核对。</p>':'')+'<label><input type="checkbox" id="import-ack">已核对上述未导入内容，只导入所选账单</label></details>':'')+'<p class="import-help">文件内重复 '+p.duplicates+' · 中性交易 '+p.neutral+' · 未付款 '+p.unpaid+'</p>'+(p.sheets||[]).map(x=>'<p class="import-help">'+esc('「'+x.name+'」'+x.records+' 行 · '+x.status)+'</p>').join("");
  const preserve=!['BILL_ALIPAY','BILL_WECHAT','BILL_BANK_ICBC','BILL_BANK_TABLE'].includes(p.source);$("#import-preserve-label").classList.toggle("hide",!preserve);$("#import-preserve").checked=preserve;$("#import-unclassified").checked=false;
  $("#import-account").innerHTML=accountOptions("");$("#import-ack")?.addEventListener("change",update);
  $("#import-upload-card").classList.add("hide");$("#import-review").classList.remove("hide");phase(2);rows();for(const el of document.querySelectorAll("[data-repair]"))el.addEventListener("click",()=>run(()=>openRepair(Number(el.dataset.repair))));
 }
 $("#import-preview").addEventListener("click",async()=>{
  if(busy)return;const file=$("#import-file").files[0],ledger=$("#import-ledger").value;
  if(!file){status("请先选择导出的账单文件。");return;}if(!ledger){status("请选择导入账本。");return;}if(file.size>20*1024*1024){status("文件超过 20 MB，请分批导入。");return;}
  busy=true;$("#import-preview").disabled=true;status("正在 NAS 上读取并核对，请稍候…");
  try{const form=new FormData();form.append("file",file);form.append("ledger_id",ledger);const result=await post("/v1/panel/import/preview",form);fileName=file.name;setPreview(result);status("请核对所选账单、账户与分类。未确认前没有新增记录。");}
  catch(e){status(e.message);}finally{busy=false;$("#import-preview").disabled=false;update();}
 });
 $("#import-commit").addEventListener("click",async()=>{
  if(busy||!preview)return;busy=true;update();status("正在保存到 NAS 同步队列…");
  try{const d=await post("/v1/panel/import/commit",{ticket:preview.ticket,revision:preview.revision,rememberMappings:$("#import-remember").checked,selected:[...selected],fallbackAccountId:$("#import-account").value,preserveCategories:$("#import-preserve").checked,allowUnclassified:$("#import-unclassified").checked,acknowledgeIssues:$("#import-ack")?.checked||false,choices});
   $("#import-review").classList.add("hide");$("#import-result").classList.remove("hide");$("#import-result-count").textContent='新增 '+d.added+' 笔，恢复 '+d.restored+' 笔，本次提交时发现并跳过 '+d.skipped+' 笔重复或新出现的重复候选。';$("#import-result-message").textContent='实际入库支出 '+money(d.expenseMinor)+' · 收入 '+money(d.incomeMinor)+'。'+(d.remembered?'已记住 '+d.remembered+' 条分类映射。':'')+(d.mappingWarning||'')+d.message;status("");phase(3);
  }catch(e){status(e.message);}finally{busy=false;update();}
 });
 for(const id of ["import-account","import-preserve","import-unclassified"])$("#"+id).addEventListener("change",update);
 for(const id of ["import-more","import-restart"])$("#"+id).addEventListener("click",()=>{if(!busy)reset();});
 $("#import-see").addEventListener("click",()=>document.querySelector('[data-tab="list"]').click());
 $("#import-catalog-refresh").addEventListener("click",()=>run(loadCatalog));
 $("#import-select-all").addEventListener("click",()=>{if(busy||!preview)return;const weak=preview.candidates.filter(c=>c.match==="SUSPECTED").length,deleted=preview.candidates.filter(c=>c.match==="DELETED").length;if((weak||deleted)&&!window.confirm("将包含 "+weak+" 笔疑似重复和 "+deleted+" 笔已删除账单。确认入库时会新增所选疑似重复，恢复已删除原记录并保留原账户和分类。是否全选并继续核对？"))return;selected=new Set(preview.candidates.map((c,i)=>c.match!=="DUPLICATE"?i:null).filter(i=>i!==null));rows();});
 $("#import-select-new").addEventListener("click",()=>{if(busy||!preview)return;selected=new Set(preview.candidates.map((c,i)=>c.match==="NEW"?i:null).filter(i=>i!==null));rows();});$("#import-select-none").addEventListener("click",()=>{if(busy||!preview)return;selected.clear();rows();});
 $("#import-prev").addEventListener("click",()=>{page--;rows();});$("#import-next").addEventListener("click",()=>{page++;rows();});
 async function openRepair(row){
  if(busy||!preview)return;busy=true;update();status('正在读取待修正行…');
  try{const d=await post('/v1/panel/import/row',{ticket:preview.ticket,revision:preview.revision,row});repairRow=row;repairOriginal=d.values||{};$("#import-repair-hint").textContent="";
   $('#import-repair-title').textContent='修正第 '+row+' 行';
   $('#import-repair-fields').innerHTML=Object.entries(repairOriginal).map(([field,value])=>'<label>'+esc(fieldNames[field]||field)+(field==='NATURE'?'<select data-repair-field="NATURE" aria-label="交易性质">'+[['','待核对'],['TRANSFER','账户划转（需填写转入账户）'],['IGNORE','不计收支'],['NORMAL','普通收支（已人工核对）']].map(([id,label])=>'<option value="'+id+'"'+(value===id?' selected':'')+'>'+label+'</option>').join('')+'</select><small>退款按普通收入导入不会抵扣原消费。</small>':'<input data-repair-field="'+esc(field)+'" aria-label="修正'+esc(fieldNames[field]||field)+'" maxlength="128" value="'+esc(value)+'">')+'<small>修改前：'+esc(value||'空')+'</small></label>').join('');
   $('#import-repair-save').disabled=!Object.keys(repairOriginal).length;$('#import-repair-dialog').showModal();status('');
  }finally{busy=false;update();}
 }
 $('#import-repair-close').addEventListener('click',()=>{if(!busy)$('#import-repair-dialog').close();});
 $('#import-repair-save').addEventListener('click',async()=>{
  if(busy||!preview)return;const changes={};for(const el of document.querySelectorAll('[data-repair-field]'))if(el.value!==repairOriginal[el.dataset.repairField])changes[el.dataset.repairField]=el.value;
  if(!Object.keys(changes).length){$('#import-repair-hint').textContent='请先修改需要修正的内容。';return;}
  busy=true;$('#import-repair-save').disabled=true;$('#import-repair-hint').textContent='正在重新校验与核对重复…';update();
  try{const d=await post('/v1/panel/import/repair',{ticket:preview.ticket,revision:preview.revision,row:repairRow,changes});setPreview(d);$('#import-repair-dialog').close();status('已重新校验，所选账单和手工分类已重置，请重新核对。仍待修正 '+d.parsed.issueCount+' 项。');$('#import-source').scrollIntoView({block:'start',behavior:'smooth'});}
  catch(e){$('#import-repair-hint').textContent=e.message;}finally{busy=false;$('#import-repair-save').disabled=false;update();}
 });
 $('#import-repair-dialog').addEventListener('cancel',e=>{if(busy)e.preventDefault();});
 $('#import-map-open').addEventListener('click',()=>{
  if(busy||!preview)return;const groups=new Map();for(const i of selected){const c=preview.candidates[i],r=c.row;if(!['NEW','SUSPECTED'].includes(c.match)||r.toAccount!=null)continue;const key=JSON.stringify([r.source,r.direction,r.category||'未提供分类']);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(i);}
  categoryGroups=[...groups.values()];$('#import-map-groups').innerHTML=categoryGroups.map((indices,g)=>{const r=preview.candidates[indices[0]].row;return '<label>'+esc(r.category||'未提供分类')+' · '+(r.direction==='INCOME'?'收入':'支出')+' '+indices.length+' 笔<select data-map-group="'+g+'" aria-label="'+esc(r.category||'未提供分类')+'目标分类"><option value="">保持当前匹配</option>'+preview.catalog.categories.filter(c=>c.kind===r.direction).map(c=>'<option value="'+esc(c.id)+'">'+esc(c.path||c.name)+'</option>').join('')+'</select></label>';}).join('')||'<p class="import-help">请先选择新账单或疑似重复账单。已存在与已删除的记录保留原分类。</p>';$('#import-map-dialog').showModal();
 });
 $('#import-map-close').addEventListener('click',()=>$('#import-map-dialog').close());
 $('#import-map-apply').addEventListener('click',()=>{let count=0;for(const el of document.querySelectorAll('[data-map-group]'))if(el.value){for(const i of categoryGroups[Number(el.dataset.mapGroup)]){choices[i]||={};choices[i].categoryId=el.value;count++;}}$('#import-map-dialog').close();rows();status('已映射 '+count+' 笔分类，核对确认后才入库。');});
 let formatColumns=[];
 const formatFields={...fieldNames,MERCHANT:"商户",NOTE:"备注",GOODS:"商品",TYPE:"交易类型",CATEGORY:"分类",SUBCATEGORY:"子分类",STATUS:"交易状态",ID:"交易号"};
 function renderFormat(mapping){$('#import-format-fields').innerHTML=Object.entries(formatFields).filter(([f])=>f!=='NATURE').map(([f,label])=>'<label>'+label+'<select data-format-field="'+f+'"><option value="-1">不使用</option>'+formatColumns.map(c=>'<option value="'+c.index+'"'+(mapping[f]===c.index?' selected':'')+'>'+esc((c.index+1)+'. '+c.header)+'</option>').join('')+'</select></label>').join('');}
 $('#import-format-open').addEventListener('click',async()=>{if(busy||!preview)return;busy=true;update();try{const p=await post('/v1/panel/import/format-plan',{ticket:preview.ticket,revision:preview.revision});formatColumns=JSON.parse(p.prompt).columns;renderFormat(preview.parsed.mapping||p.mapping||{});$('#import-format-message').textContent='';$('#import-format-dialog').showModal();}catch(e){status(e.message)}finally{busy=false;update()}});
 $('#import-format-close').addEventListener('click',()=>{if(!busy){$('#import-format-dialog').close();$('#import-ai-key').value=''}});
 $('#import-format-dialog').addEventListener('cancel',e=>{if(busy)e.preventDefault();else $('#import-ai-key').value=''});
 $('#import-format-ai').addEventListener('click',async()=>{if(busy||!preview)return;busy=true;$('#import-format-message').textContent='正在获取字段建议…';try{const r=await post('/v1/panel/import/format-ai',{ticket:preview.ticket,revision:preview.revision,baseURL:$('#import-ai-url').value,model:$('#import-ai-model').value,apiKey:$('#import-ai-key').value});renderFormat(r.mapping);$('#import-format-message').textContent='建议已填入，请核对后采用。原预览没有变化。'}catch(e){$('#import-format-message').textContent=e.message}finally{$('#import-ai-key').value='';busy=false;update()}});
 $('#import-format-apply').addEventListener('click',async()=>{if(busy||!preview)return;const mapping={};for(const el of document.querySelectorAll('[data-format-field]'))if(Number(el.value)>=0)mapping[el.dataset.formatField]=Number(el.value);busy=true;try{const r=await post('/v1/panel/import/format-apply',{ticket:preview.ticket,revision:preview.revision,mapping});setPreview(r);$('#import-format-dialog').close();$('#import-ai-key').value='';status('字段已重新校验，请核对所选账单、分类与疑似重复。')}catch(e){$('#import-format-message').textContent=e.message}finally{busy=false;update()}});
 return {loadCatalog,reset,refName:(entity,id)=>preview?.catalog[entity]?.find(x=>x.id===id)?.name};
})();
