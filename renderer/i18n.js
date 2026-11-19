"use strict";
(() => {
  const catalog = globalThis.FA_TRANSLATIONS || {};
  let language = (globalThis.arena?.cfg || globalThis.host?.cfg || {}).language || "tr";
  const texts = new WeakMap(), attrs = new WeakMap();
  const excluded = "script,style,textarea,[data-i18n-ignore],.chip .nm,.qrow .txt,.bar b,.msg .nm,.msg .txt:not(.unsup),.quote,.bn,#reply b,#reply span,.avatarChoice span,input";
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
      const d=document.createElement("dialog"); d.className="language-dialog";
      d.setAttribute("aria-labelledby","languageTitle");d.setAttribute("data-i18n-ignore","");
      const header=document.createElement("div");header.className="language-header";
      const logo=document.querySelector(".brand svg")?.cloneNode(true);if(logo){logo.setAttribute("aria-hidden","true");header.append(logo);}
      const brand=document.createElement("strong");brand.textContent="FOOTBALL ARENA";header.append(brand);
      const body=document.createElement("div");body.className="language-body";
      const heading=document.createElement("h2");heading.id="languageTitle";heading.textContent="DİL / LANGUAGE";
      const subtitle=document.createElement("p");subtitle.className="language-subtitle";
      subtitle.append(document.createTextNode("Football Arena için dil seçin."),document.createElement("br"),document.createTextNode("Select a language for Football Arena."));
      const choices=document.createElement("div");choices.className="language-options";choices.setAttribute("role","group");choices.setAttribute("aria-label","Dil / Language");
      let selected=language;
      const buttons=[];
      const save=document.createElement("button");save.className="primary language-continue";
      const note=document.createElement("p");note.className="language-note";
      const error=document.createElement("p");error.className="language-error";error.setAttribute("role","alert");error.hidden=true;
      function paint(){buttons.forEach(b=>b.setAttribute("aria-pressed",String(b.dataset.language===selected)));save.textContent=selected==="tr"?"Devam et →":"Continue →";note.textContent=selected==="tr"?"Tercihiniz kaydedilir. Ayarlar’dan değiştirebilirsiniz.":"Your preference is saved. Change it anytime in Settings.";}
      for(const [value,label,flag] of [["tr","Türkçe","tr"],["en","English","us"]]) {
        const button=document.createElement("button");button.type="button";button.dataset.language=value;
        const image=document.createElement("img");image.src="flags/"+flag+".svg";image.alt=flag==="tr"?"Türkiye":"United States";
        const text=document.createElement("span");text.textContent=label;button.append(image,text);
        button.onclick=()=>{selected=value;error.hidden=true;paint();};buttons.push(button);choices.append(button);
      }
      save.onclick=async()=>{save.disabled=true;try{await setLanguage(selected);d.close();d.remove();}catch{error.textContent=selected==="tr"?"Dil kaydedilemedi. Lütfen tekrar deneyin.":"Could not save your language. Please try again.";error.hidden=false;}finally{save.disabled=false;}};
      paint();body.append(heading,subtitle,choices,save,note,error);d.append(header,body);
      d.addEventListener("cancel",e=>e.preventDefault());document.body.append(d);
      d.addEventListener("close",()=>globalThis.FAI?.refreshOverlay());
      if(globalThis.FAI) FAI.showDialog(d);else d.showModal();
    }
  }
  if(document.readyState==="loading") document.addEventListener("DOMContentLoaded",start);else start();
})();
