"use strict";
(() => {
  const catalog = globalThis.FA_TRANSLATIONS || {};
  let language = (globalThis.arena?.cfg || globalThis.host?.cfg || {}).language || "tr";
  const texts = new WeakMap(), attrs = new WeakMap();
  const excluded = "script,style,textarea,.chip .nm,.qrow .txt,.bar b,.msg .nm,.msg .txt:not(.unsup),.quote,.bn,#reply b,#reply span,.avatarChoice span,input";
  const escape = s => s.replace(/[.*+?^${}()|[\]\\]/g,"\\$&");
  const phrases = Object.keys(catalog).filter(s=>!s.includes("{0}")).sort((a,b) => b.length-a.length);
  const templates = Object.keys(catalog).filter(s=>s.includes("{0}")).map(key=>[new RegExp("^"+key.split(/\{\d+\}/).map(escape).join("(.+?)")+"$"),catalog[key]]);
  function t(source) {
    if (language !== "tr" || !source) return source;
    if (catalog[source]) return catalog[source];
    for(const [pattern, value] of templates) { const match=source.match(pattern);if(match)return value.replace(/\{(\d+)\}/g,(_,n)=>match[Number(n)+1]); }
    return source.replace(new RegExp(phrases.map(s => s.replace(/[.*+?^${}()|[\]\\]/g,"\\$&")).join("|"), "g"), (word, offset) => {
      const before = source[offset-1], after = source[offset+word.length];
      return (before && /[A-Za-z]/.test(before)) || (after && /[A-Za-z]/.test(after)) ? word : catalog[word];
    });
  }
  function apply(root = document.body) {
    if (!root) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    let node;
    while ((node=walker.nextNode())) {
      if (!node.parentElement || node.parentElement.closest(excluded)) continue;
      const prior=texts.get(node), current=node.nodeValue;
      const original=prior && current===prior.rendered ? prior.source : current;
      const rendered=t(original);
      texts.set(node,{source:original,rendered}); if (current!==rendered) node.nodeValue=rendered;
    }
    root.querySelectorAll("[title],[placeholder],[aria-label]").forEach(element => {
      let record=attrs.get(element); if(!record) attrs.set(element,record={});
      for(const key of ["title","placeholder","aria-label"]) {
        const current=element.getAttribute(key); if(current==null) continue;
        const prior=record[key], source=prior && current===prior.rendered ? prior.source : current, rendered=t(source);
        record[key]={source,rendered}; if(current!==rendered) element.setAttribute(key,rendered);
      }
    });
    document.documentElement.lang=language;
  }
  async function setLanguage(next) {
    if(!["tr","en"].includes(next)) return;
    await arena.setCfg({language:next,languageChosen:true}); language=next; apply();
  }
  globalThis.FA_I18N={t,apply,get language(){return language},setLanguage};
  function start() {
    apply();
    let pending=false;
    new MutationObserver(() => { if(pending) return; pending=true; queueMicrotask(() => { pending=false; apply(); }); }).observe(document.body,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:["title","placeholder","aria-label"]});
    const bridge=globalThis.arena || globalThis.host;
    (bridge?.onCfg || bridge?.onSettings)?.(cfg => { if(cfg.language && cfg.language!==language){language=cfg.language;apply();} });
    if(globalThis.arena && !arena.cfg.languageChosen) {
      const d=document.createElement("dialog"); d.className="sdlg";
      const heading=document.createElement("h2"); heading.textContent="Choose language";
      const select=document.createElement("select"); select.setAttribute("aria-label","Language");
      for(const [value,label] of [["tr","Türkçe"],["en","English"]]) { const option=document.createElement("option"); option.value=value;option.textContent=label;select.append(option); }
      select.value=language;
      const save=document.createElement("button");save.className="primary";save.textContent="Continue";save.onclick=async()=>{await setLanguage(select.value);d.close();d.remove();};
      d.addEventListener("cancel",e=>e.preventDefault()); d.append(heading,select,save);document.body.append(d);
      d.addEventListener("close",()=>globalThis.FAI?.refreshOverlay());
      if(globalThis.FAI) FAI.showDialog(d);else d.showModal();
    }
  }
  if(document.readyState==="loading") document.addEventListener("DOMContentLoaded",start);else start();
})();
