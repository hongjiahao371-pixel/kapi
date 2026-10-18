"use strict";
(() => {
  const tools=document.getElementById("view-tools"),pair=document.getElementById("pair-card");
  if(API_BASE){pair.classList.add("hide");return;}
  const card=document.createElement("div");card.className="tool-card card family-card";
  card.innerHTML='<div class="tool-icon">'+svg("home")+'</div><h2>家庭成员</h2><p>家庭账本一起记，个人账本分别保存。创建后不会分享已有的个人账单。</p><div id="family-state" role="status"></div><div id="family-actions"></div><div id="family-roster"></div><div id="family-error" class="err" role="alert"></div>';
  pair.after(card);
  async function request(path,body){
    const response=await fetch(path,{method:body===undefined?"GET":"POST",headers:{"X-Panel-Token":S.token,"Content-Type":"application/json"},body:body===undefined?undefined:JSON.stringify(body),cache:"no-store"});
    const value=await response.json();if(!response.ok)throw new Error(value.message||"家庭管理暂时不可用");return value;
  }
  function button(label,action,primary=false){const b=document.createElement("button");b.type="button";b.className="btn "+(primary?"primary":"ghost");b.textContent=label;b.addEventListener("click",async()=>{b.disabled=true;$("#family-error").textContent="";try{await action();}catch(e){$("#family-error").textContent=e.message;}finally{b.disabled=false;}});return b;}
  async function load(){
    try{
      const state=await request("/v1/family/status"),actions=$("#family-actions"),roster=$("#family-roster");actions.replaceChildren();roster.replaceChildren();
      $("#family-state").textContent=state.enabled?"家庭已启用 · "+"成员平等 · 仅记账人本人可修改、删除或恢复账单":"尚未创建家庭账本";
      if(!state.enabled){actions.append(button("创建家庭账本",async()=>{await request("/v1/family/enable",{allow_member_edits:false});await load();},true));return;}
      actions.append(button("打开家庭账本",()=>{
        const path="/family/space/shared/"+encodeURIComponent(state.shared_id)+"/panel/";
        // Each space has its own saved browser identity. No token in URL requests.
        location.assign(path+"#access="+encodeURIComponent(S.token));
      },true));
      const label=document.createElement("label");label.textContent="邀请成员（昵称）";label.htmlFor="family-member-name";label.style.marginTop="18px";
      const input=document.createElement("input");input.id="family-member-name";input.placeholder="例如：家人、小明";input.maxLength=24;input.autocomplete="off";
      const invitation=document.createElement("div");invitation.setAttribute("role","status");invitation.style.cssText="overflow-wrap:anywhere;margin-top:12px;line-height:1.7";
      actions.append(label,input,button("生成家庭邀请码",async()=>{if(!input.value.trim())throw new Error("请先填写成员昵称");const v=await request("/v1/family/invite",{name:input.value.trim()});invitation.replaceChildren();const p=document.createElement("p");p.textContent="请在归账“设置 → 家庭账本”加入，邀请码仅可使用一次，15 分钟有效。";const code=document.createElement("code");code.textContent=v.code;invitation.append(p,code,button("复制邀请信息",async()=>{const text="归账家庭邀请\nNAS："+location.origin+"\n成员："+v.name+"\n邀请码："+v.code+"\n15 分钟内在“设置 → 家庭账本”加入";if(navigator.clipboard&&window.isSecureContext)await navigator.clipboard.writeText(text);else{const area=document.createElement("textarea");area.value=text;invitation.append(area);area.select();if(!document.execCommand("copy"))throw new Error("请长按选中邀请码复制");area.remove();}}));}),invitation);
      for(const m of state.members||[]){const row=document.createElement("div");row.style.cssText="display:flex;align-items:center;gap:12px;padding:14px 0;border-bottom:1px solid var(--line,rgba(0,0,0,.06))";const name=document.createElement("span");name.textContent=m.name+(m.active?" · 已连接":" · 已断开");name.style.flex="1";row.append(name);if(m.id!=="owner"&&m.active)row.append(button("断开连接",async()=>{if(!confirm("断开“"+m.name+"”的连接后，该成员不能再访问 NAS 的个人和家庭空间，已下载到手机的数据仍会保留。继续吗？"))return;await request("/v1/family/revoke",{member_id:m.id});await load();}));roster.append(row);}
    }catch(e){$("#family-error").textContent=e.message;}
  }
  window.FamilyPanel={load};
})();
