(() => {
  'use strict';
  if (window.ReadX && typeof window.ReadX.selection === 'function') return true;
  let nodeCache=null, textCache=null, nodeOffsets=new WeakMap();
  const invalidate = () => { nodeCache=null; textCache=null; nodeOffsets=new WeakMap(); };
  const nodes = () => {
    if(nodeCache) return nodeCache;
    const out = []; let at = 0;
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode(n) { return n.parentElement && !n.parentElement.closest('script,style,noscript') ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT; }
    });
    for (let n; (n = walker.nextNode());) { nodeOffsets.set(n,at); out.push({node:n, start:at, end:at+n.length}); at += n.length; }
    nodeCache=out; return out;
  };
  const text = list => textCache ?? (textCache=list.map(n => n.node.data).join(''));
  function locate(anchor, list) {
    const all = text(list); let start = anchor.start, end = anchor.end;
    if (Number.isInteger(start) && Number.isInteger(end) && start >= 0 && end > start && end <= all.length && all.slice(start,end) === anchor.quote &&
      (!anchor.prefix || all.slice(Math.max(0,start-anchor.prefix.length),start) === anchor.prefix) &&
      (!anchor.suffix || all.slice(end,end+anchor.suffix.length) === anchor.suffix)) return {start,end};
    if (!anchor.quote) return null;
    let match = null, from = 0;
    while (from <= all.length) {
      const i = all.indexOf(anchor.quote,from); if (i < 0) break;
      const j = i+anchor.quote.length;
      if ((!anchor.prefix || all.slice(Math.max(0,i-anchor.prefix.length),i) === anchor.prefix) &&
          (!anchor.suffix || all.slice(j,j+anchor.suffix.length) === anchor.suffix)) {
        if (match) return null; match = {start:i,end:j};
      }
      from = i+1;
    }
    return match;
  }
  function rangeFor(position,list) {
    const a = list.find(n => n.end > position.start);
    const b = list.find(n => n.end >= position.end && n.start < position.end);
    if (!a || !b) return null;
    const r = document.createRange(); r.setStart(a.node,position.start-a.start); r.setEnd(b.node,position.end-b.start); return r;
  }
  function anchor(start,end,list) {
    const all = text(list);
    let p=Math.max(0,start-40), q=Math.min(all.length,end+40);
    if(p>0 && /[\uDC00-\uDFFF]/.test(all[p])) p++;
    if(q<all.length && /[\uD800-\uDBFF]/.test(all[q-1])) q--;
    return {start,end,quote:all.slice(start,end),prefix:all.slice(p,start),suffix:all.slice(end,q)};
  }
  function preserveSelection(paged,direction) {
    const a=selection(); if(!a) return false;
    const pageWidth=innerWidth, next=Math.max(0,Math.min(document.documentElement.scrollWidth-innerWidth,scrollX+direction*pageWidth));
    window.scrollTo(next,scrollY);
    const list=nodes(),position=locate(a,list),range=position && rangeFor(position,list);
    if(range) {const s=getSelection();s.removeAllRanges();s.addRange(range);return true;} return false;
  }
  function extendSelection(direction) {
    const s=getSelection();if(!s || !s.rangeCount || s.isCollapsed) return null;
    if(typeof s.modify==='function') s.modify('extend',direction>0?'forward':'backward','line');
    return selectionInfo();
  }
  function flash(a) {
    const list=nodes(),position=locate(a,list);if(!position)return false;
    const range=rangeFor(position,list);if(!range)return false;
    const layers=[];
    for(const box of range.getClientRects()) {
      if(box.right<=0||box.left>=innerWidth||box.bottom<=0||box.top>=innerHeight)continue;
      const layer=document.createElement('div');layer.setAttribute('aria-hidden','true');
      layer.style.cssText=`position:fixed;pointer-events:none;z-index:2147483646;left:${box.left}px;top:${box.top}px;width:${box.width}px;height:${box.height}px;`;
      layer.style.setProperty('background-color','rgba(36,107,252,.28)','important');document.body.appendChild(layer);layers.push(layer);
    }
    let count=0;const timer=setInterval(()=>{for(const layer of layers)layer.style.opacity=(count%2)?'1':'0';if(++count>=6){clearInterval(timer);layers.forEach(layer=>layer.remove());}},180);
    return true;
  }
  function snapSelection(unit) {
    const a=selection();if(!a)return null;
    const list=nodes(),all=text(list);let start=a.start,end=a.end;
    if(typeof Intl.Segmenter==='function') {
      const segmenter=new Intl.Segmenter('zh',{granularity:unit==='SENTENCE'?'sentence':'word'});
      const lo=Math.max(0,start-256),hi=Math.min(all.length,end+256);
      for(const segment of segmenter.segment(all.slice(lo,hi))) {
        const i=lo+segment.index,j=i+segment.segment.length;
        if(i<start && j>start)start=i;
        if(i<end && j>end)end=j;
      }
    } else {
      const word=c=>/[\p{L}\p{N}]/u.test(c);
      if(unit==='WORD') {while(start>0 && start>a.start-32 && word(all[start-1]))start--;while(end<all.length && end<a.end+32 && word(all[end]))end++;}
    }
    while(start<end && /\s/.test(all[start]))start++;
    while(end>start && /\s/.test(all[end-1]))end--;
    if(start>0 && /[\uDC00-\uDFFF]/.test(all[start]))start--;
    if(end<all.length && /[\uD800-\uDBFF]/.test(all[end-1]))end++;
    if(end-start>16384)return selectionInfo();
    const range=rangeFor({start,end},list);if(range) {const s=getSelection();s.removeAllRanges();s.addRange(range);}
    return selectionInfo();
  }
  function selection() {
    const s = window.getSelection(); if (!s || s.isCollapsed || !s.rangeCount) return null;
    const r = s.getRangeAt(0); if (!document.body.contains(r.startContainer) || !document.body.contains(r.endContainer)) return null;
    const before = document.createRange(); before.selectNodeContents(document.body); before.setEnd(r.startContainer,r.startOffset);
    // All rendered body text participates in the same UTF-16 coordinate system; scripts/styles are removed by LocalHtml.
    const start = before.toString().length, selected = r.toString();
    if (!selected.trim() || selected.length > 16384) return null;
    return anchor(start,start+selected.length,nodes());
  }
  function viewportAnchor() {
    const list = nodes();
    // Chromium caret hit-testing starts at the viewport, not at the beginning of a giant chapter.
    for(const y of [24,48,80]) for(const x of [Math.min(innerWidth-24,Math.max(24,parseFloat(getComputedStyle(document.body).paddingLeft)+4)),innerWidth/2]) {
      const caret=document.caretRangeFromPoint(x,y);
      if(!caret || caret.startContainer.nodeType!==Node.TEXT_NODE) continue;
      const start=nodeOffsets.get(caret.startContainer);
      if(start===undefined || !caret.startContainer.data.trim()) continue;
      const at=caret.startOffset, end=Math.min(at+32,caret.startContainer.length);
      if(end>at) return anchor(start+at,start+end,list);
    }
    for (const n of list) {
      if (!n.node.data.trim()) continue;
      const r = document.createRange(); r.selectNodeContents(n.node);
      if (![...r.getClientRects()].some(b => b.right>0 && b.left<innerWidth && b.bottom>0 && b.top<innerHeight)) continue;
      // Find a visible character, not the start of a paragraph split over several pages.
      for (let lo=0, hi=n.node.length, count=0; count<24 && lo<hi; count++) {
        const mid=Math.floor((lo+hi)/2); r.setStart(n.node,mid); r.setEnd(n.node,Math.min(mid+1,n.node.length));
        const b=r.getBoundingClientRect();
        if (b.right<=0 || b.bottom<=0) lo=mid+1; else hi=mid;
        if (lo===hi) {
          r.setStart(n.node,lo); r.setEnd(n.node,Math.min(lo+1,n.node.length)); const c=r.getBoundingClientRect();
          if (c.right>0 && c.left<innerWidth && c.bottom>0 && c.top<innerHeight) return anchor(n.start+lo,n.start+Math.min(lo+32,n.node.length),list);
        }
      }
    }
    return null;
  }
  let resolvedMarks=[];
  function visibleRect(range) {
    const boxes=[...range.getClientRects()].filter(b=>b.right>0 && b.left<innerWidth && b.bottom>0 && b.top<innerHeight);
    if(!boxes.length) return [0,0,0,0];
    return [Math.max(0,Math.min(...boxes.map(b=>b.left))),Math.max(0,Math.min(...boxes.map(b=>b.top))),
      Math.min(innerWidth,Math.max(...boxes.map(b=>b.right))),Math.min(innerHeight,Math.max(...boxes.map(b=>b.bottom)))];
  }
  function selectionInfo() {
    const a=selection();if(!a) return null;
    const r=rangeFor(a,nodes());if(!r) return null;
    return {anchor:a,rect:visibleRect(r),ids:resolvedMarks.filter(m=>m.p.start<a.end && m.p.end>a.start).map(m=>m.a.id),fromMark:false};
  }
  function markAt(x,y) {
    const element=document.elementFromPoint(x,y), span=element && element.closest('[data-readx-ids]');if(!span) return null;
    const ids=JSON.parse(span.dataset.readxIds), matches=resolvedMarks.filter(m=>ids.includes(m.a.id));
    if(!matches.length) return null;
    const chosen=matches.sort((a,b)=>(b.a.updatedAt||0)-(a.a.updatedAt||0))[0];
    const list=nodes(), r=rangeFor(chosen.p,list);if(!r) return null;
    return {anchor:anchor(chosen.p.start,chosen.p.end,list),rect:visibleRect(r),ids,fromMark:true,primaryId:chosen.a.id};
  }
  function marks(items) {
    document.querySelectorAll('[data-readx-mark]').forEach(e => e.replaceWith(...e.childNodes));
    document.body.normalize();invalidate();const initial=nodes(), all=text(initial);
    const resolveFast=a=> {
      if(a.start>=0 && a.end<=all.length && all.slice(a.start,a.end)===a.quote &&
        (!a.prefix || all.slice(Math.max(0,a.start-a.prefix.length),a.start)===a.prefix) &&
        (!a.suffix || all.slice(a.end,a.end+a.suffix.length)===a.suffix)) return {start:a.start,end:a.end};
      return locate(a,initial);
    };
    resolvedMarks=items.map(a=>({a,p:resolveFast(a.anchor)})).filter(m=>m.p);
    const events=[];
    resolvedMarks.forEach((m,i)=>{events.push({at:m.p.start,i,add:true},{at:m.p.end,i,add:false});});
    events.sort((a,b)=>a.at-b.at || Number(a.add)-Number(b.add));
    const active=new Set(),segments=[];let previous=0,at=0;
    while(at<events.length) {
      const edge=events[at].at;
      if(edge>previous && active.size) {
        const current=[...active].map(i=>resolvedMarks[i]);
        const newest=kind=>current.filter(m=>m.a.kind===kind).sort((a,b)=>(b.a.updatedAt||0)-(a.a.updatedAt||0))[0];
        segments.push({start:previous,end:edge,ids:current.map(m=>m.a.id),highlight:newest('HIGHLIGHT'),underline:newest('UNDERLINE'),note:newest('NOTE')});
      }
      while(at<events.length && events[at].at===edge) {const e=events[at++];if(e.add) active.add(e.i);else active.delete(e.i);}
      previous=edge;
    }
    // Each text interval receives one flat wrapper. Even legacy duplicates/overlaps cannot compound alpha.
    let ni=initial.length-1;
    for(const segment of segments.reverse()) {
      while(ni>=0 && initial[ni].start>=segment.end) ni--;
      for(let i=ni;i>=0 && initial[i].end>segment.start;i--) {
        const n=initial[i];let part=n.node;
        const stop=Math.min(n.end,segment.end)-n.start,start=Math.max(n.start,segment.start)-n.start;
        if(stop<part.length) part.splitText(stop);if(start>0) part=part.splitText(start);
        const mark=document.createElement('span');mark.dataset.readxMark='1';mark.dataset.readxIds=JSON.stringify(segment.ids);
        const safeColor=m=>/^#[0-9a-f]{6}$/i.test(m.a.color||'') ? m.a.color : '#FFD240';
        if(segment.highlight) {
          const c=safeColor(segment.highlight),v=parseInt(c.slice(1),16);
          mark.style.setProperty('background-color',`rgba(${v>>16},${(v>>8)&255},${v&255},0.32)`,'important');
        }
        if(segment.underline || segment.note) {
          const m=segment.underline || segment.note;
          mark.style.setProperty('text-decoration-line','underline','important');
          mark.style.setProperty('text-decoration-color',safeColor(m),'important');
          mark.style.setProperty('text-decoration-style',segment.underline ? 'solid' : 'dotted','important');
          mark.style.setProperty('text-decoration-thickness','2px','important');
        }
        part.replaceWith(mark);mark.appendChild(part);
      }
    }
    invalidate();return {resolved:resolvedMarks.length,total:items.length};
  }
  // Recolour/edit metadata in-place. No text-node replacement, normalize(), or pagination invalidation.
  function restyle(items) {
    const values=new Map(items.map(a=>[a.id,a]));
    resolvedMarks=resolvedMarks.map(m=>({a:values.get(m.a.id)||m.a,p:m.p}));
    document.querySelectorAll('[data-readx-mark]').forEach(mark=>{
      const ids=JSON.parse(mark.dataset.readxIds||'[]'), rows=ids.map(id=>values.get(id)).filter(Boolean);
      const newest=kind=>rows.filter(a=>a.kind===kind).sort((a,b)=>(b.updatedAt||0)-(a.updatedAt||0))[0];
      const highlight=newest('HIGHLIGHT'), underline=newest('UNDERLINE'), note=newest('NOTE');
      const safe=a=>/^#[0-9a-f]{6}$/i.test(a.color||'') ? a.color : '#FFD240';
      if(highlight) {const v=parseInt(safe(highlight).slice(1),16);mark.style.setProperty('background-color',`rgba(${v>>16},${(v>>8)&255},${v&255},0.32)`,'important');}
      if(underline||note) {const a=underline||note;mark.style.setProperty('text-decoration-color',safe(a),'important');mark.style.setProperty('text-decoration-style',underline?'solid':'dotted','important');}
    });
    return {resolved:resolvedMarks.length,total:items.length};
  }
  function navigate(a,paged) {
    const list=nodes(), p=locate(a,list); if (!p) return {found:false};
    const r=rangeFor({start:p.start,end:Math.min(p.start+1,p.end)},list); if (!r) return {found:false};
    const b=r.getBoundingClientRect();
    if (!paged) window.scrollTo(0,Math.max(0,b.top+scrollY-20));
    return {found:true,page:Math.floor((b.left+scrollX)/parseFloat(getComputedStyle(document.body).width)+0.001)+1};
  }
  function sourcePage() {
    const list=nodes();
    for (const n of list) {
      if (!n.node.data.trim()) continue;
      const e=n.node.parentElement.closest('[data-source-page]'); if (!e) continue;
      const r=document.createRange(); r.selectNodeContents(n.node);
      if ([...r.getClientRects()].some(b => b.right>0 && b.left<innerWidth && b.bottom>0 && b.top<innerHeight)) {
        const value=Number(e.getAttribute('data-source-page'));
        if(Number.isInteger(value) && value>=0) return value;
      }
    }
    for (const image of document.images) {
      const e=image.closest('[data-source-page]'); const b=image.getBoundingClientRect();
      if(e && b.right>0 && b.left<innerWidth && b.bottom>0 && b.top<innerHeight) return Number(e.getAttribute('data-source-page')) || 0;
    }
    return 0;
  }
  window.ReadX = Object.freeze({flash,snapSelection,preserveSelection,extendSelection,sourcePage,selection,selectionInfo,markAt,viewportAnchor,marks,restyle,navigate,
    interactiveAt(x,y) { const e=document.elementFromPoint(x,y); return !!(e && e.closest('a,[data-readx-mark]')); },
    clearSelection() { const s=window.getSelection(); if(s) s.removeAllRanges(); },
    metrics() { return {width:innerWidth,height:innerHeight,pages:Math.max(1,Math.ceil((document.documentElement.scrollWidth-innerWidth)/innerWidth-.01)+1)}; }
  });
  return true;
})();
