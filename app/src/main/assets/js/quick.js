setTimeout(function(){let t=function(t){let e=t.questionVO.targetCostTime;return e>0?e-233:0},e=function(e){let r=0,i=window._quick_mode_must_win,n=window._quick_mode_interval&&0!=window._quick_mode_interval?window._quick_mode_interval:0;for(let o of e.questionList){let s={answer:1,pathPoints:[[{x:233.3333,y:466.6666},],],recognizeResult:o.answer,showReductionFraction:0},a=JSON.parse(JSON.stringify(o));a.script=JSON.stringify(s.pathPoints),a.status=1,a.curTrueAnswer=s,e.exerciseRecord.push(a),e.correctCount+=1,e.questionIndex+=1,e.curTrueAnswer=s,r+=Math.floor(n*(1+.25*Math.random()))}e.pauseTimer();let u=t(e);r=i&&u>0?u:r,e.costTime=r>0?r:1},r=window.VUE_APP.$root.$children[0].$children[0];if(r._setupProxy.showMatching){for(let i of r.$children)if("matching"==i.$el._prevClass){i._backup_startPlay=i.startPlay,i.startPlay=function(){console.log("startPlay");let t=i._backup_startPlay(arguments);return e(r._setupProxy),t};break}}else e(r._setupProxy);console.log("js injected")},0);