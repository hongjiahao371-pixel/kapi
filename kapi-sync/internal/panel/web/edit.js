/* Existing-row edits only. Request identity survives an ambiguous network retry. */
(()=>{
 let generation=0,current=null,busy=false,attempt=null,conflicted=false;
 const dialog=$('#bill-edit-dialog'),status=$('#bill-edit-status'),save=$('#bill-edit-save');
 const fields=['amount','category','account','note'];
 const plain=value=>{for(let i=0;i<8&&typeof value==='string';i++){const s=value.trim();if(s==='null')return '';if(!s.startsWith('"')||!s.endsWith('"'))break;try{value=JSON.parse(s)}catch{break}}return value==null?'':String(value)};
 function nonce(){const bytes=new Uint8Array(16);crypto.getRandomValues(bytes);return Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('')}
 function update(){save.disabled=busy||conflicted;save.textContent=busy?'正在保存…':'保存并同步';$('#bill-edit-cancel').disabled=busy;$('#bill-edit-close').disabled=busy;$('#bill-edit-reload').disabled=busy;for(const name of fields)$('#bill-edit-'+name).disabled=busy||(name==='category'&&!!plain(current?.record.values.to_account_id))}
 function fillOptions(node,items,selected,empty){node.replaceChildren();if(empty)node.add(new Option(empty,''));for(const item of items)node.add(new Option(item.path||item.name||item.id,item.id));if(selected&&!Array.from(node.options).some(o=>o.value===selected))node.add(new Option('原值（当前不可新选） · '+selected,selected));node.value=selected}
 function display(editor){current=editor;attempt=null;conflicted=false;const v=editor.record.values;
  const cents=BigInt(plain(v.amount_minor)||'0');$('#bill-edit-amount').value=(cents/100n)+'.'+String(cents%100n).padStart(2,'0');
  fillOptions($('#bill-edit-category'),editor.catalog.categories.filter(c=>c.kind===plain(v.direction)),plain(v.category_id),'未分类');
  fillOptions($('#bill-edit-account'),editor.catalog.accounts,plain(v.account_id),null);
  $('#bill-edit-note').value=plain(v.note);$('#bill-edit-name').textContent=plain(v.merchant)||'未命名账单';
  $('#bill-edit-kind').textContent=plain(v.to_account_id)?'账户划转':plain(v.direction)==='INCOME'?'收入':'支出';
  status.textContent='';$('#bill-edit-reload').hidden=true;update();
 }
 async function open(id){const seq=++generation;try{const editor=await api('/v1/panel/edit',{id});if(seq!==generation||S.sel!==id)return;if(!editor.can_edit)throw new Error(editor.record.deleted?'已删除账单不能编辑':'家庭账本只能修改自己记录的账单');display(editor);dialog.showModal();$('#bill-edit-amount').focus()}catch(e){if(seq===generation)reportError(e)}}
 async function attach(id){const seq=++generation,button=$('#bill-edit-open');if(!button)return;button.disabled=true;
  try{const editor=await api('/v1/panel/edit',{id});if(seq!==generation||S.sel!==id)return;button.disabled=!editor.can_edit;$('#bill-edit-hint').textContent=editor.can_edit?'修改原账单，手机下次同步后更新。':editor.record.deleted?'已删除账单不能编辑。':'家庭账本只能修改自己记录的账单。';button.onclick=()=>open(id)}
  catch(e){if(seq===generation&&S.sel===id)$('#bill-edit-hint').textContent=e.message}
 }
 async function submit(e){e.preventDefault();if(busy||conflicted||!current)return;
  const payload={id:current.record.entity_id,revision:current.revision,amount:$('#bill-edit-amount').value.trim(),category_id:$('#bill-edit-category').value,account_id:$('#bill-edit-account').value,note:$('#bill-edit-note').value.trim()};
  if(!/^(?:0|[1-9][0-9]{0,8})(?:\.[0-9]{1,2})?$/.test(payload.amount)||!/[1-9]/.test(payload.amount)){status.textContent='请输入大于 0、最多两位小数的金额';$('#bill-edit-amount').focus();return}
  const serialized=JSON.stringify(payload);if(!attempt||attempt.serialized!==serialized)attempt={serialized,body:{...payload,request_id:nonce()}};
  const requestToken=S.token,seq=generation;busy=true;status.textContent='';update();let result;
  try{const response=await fetch(apiPath('/v1/panel/edit'),{method:'POST',headers:{'Content-Type':'application/json','X-Panel-Token':requestToken},body:JSON.stringify(attempt.body),cache:'no-store'});
   const data=await response.json();if(seq!==generation||S.token!==requestToken)return;
   if(!response.ok){if(response.status===409){conflicted=true;$('#bill-edit-reload').hidden=false}if(response.status===401)signOut();throw new Error(data.message||'保存失败，请稍后重试')};result=data;
  }catch(error){if(seq===generation){status.textContent=error.message||'连接中断，可重试保存。';save.textContent='重试保存'}return}
  finally{busy=false;if(seq===generation)update()}
  if(seq!==generation||!result)return;
  dialog.close();closeRow();$('#bill-save-status').textContent=(result.unchanged?'内容未变更。':result.replayed?'这次修改此前已保存，现已读取最新账单。':'已保存到 NAS。')+'手机下次同步后更新原账单；可在手机点击“立即同步”。';$('#bill-save-status').hidden=false;
  try{await loadAll()}catch(error){reportError(error)}
 }
 function cancel(){if(busy)return;dialog.close();current=null;attempt=null;$('#bill-edit-note').value='';$('#bill-edit-open')?.focus()}
 $('#bill-edit-form').addEventListener('submit',submit);
 for(const id of ['bill-edit-close','bill-edit-cancel'])$('#'+id).addEventListener('click',cancel);
 dialog.addEventListener('cancel',e=>{e.preventDefault();cancel()});
 $('#bill-edit-reload').addEventListener('click',async()=>{if(busy||!current)return;const seq=generation;try{const editor=await api('/v1/panel/edit',{id:current.record.entity_id});if(seq!==generation)return;if(!editor.can_edit)throw new Error('这笔账单当前不可编辑');display(editor)}catch(error){if(seq===generation)status.textContent=error.message}});
 window.NasEdit={attach,reset(){generation++;busy=false;current=null;attempt=null;if(dialog.open)dialog.close();for(const name of fields)$('#bill-edit-'+name).value='';$('#bill-save-status').hidden=true}};
})();
