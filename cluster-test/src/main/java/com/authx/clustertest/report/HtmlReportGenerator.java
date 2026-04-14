package com.authx.clustertest.report;

import com.authx.clustertest.config.ResultsRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a single self-contained HTML file with embedded Chart.js and
 * inline JSON data from each test phase. Opens correctly without network.
 */
@Component
public class HtmlReportGenerator {
    private final ResultsRepo repo;
    private final EnvironmentInfo env;
    private final ObjectMapper mapper = new ObjectMapper();

    public HtmlReportGenerator(ResultsRepo r, EnvironmentInfo e) {
        this.repo = r; this.env = e;
    }

    @SuppressWarnings("rawtypes")
    public Path generate() throws IOException {
        String chartJs = new String(
                new ClassPathResource("web/chart.min.js").getInputStream().readAllBytes());

        var data = new LinkedHashMap<String, Object>();
        data.put("env", env.snapshot());
        data.put("correctness", repo.read("correctness", Map.class));
        data.put("baseline", repo.read("baseline", Map.class));
        data.put("resilience", repo.read("resilience", Map.class));
        data.put("stress", repo.read("stress", Map.class));
        data.put("soak", repo.read("soak", Map.class));

        String json = mapper.writeValueAsString(data);
        String html = buildHtml(chartJs, json);

        Path out = repo.baseDir().resolveSibling("report.html");
        Files.writeString(out, html);
        return out;
    }

    private String buildHtml(String chartJs, String json) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<title>AuthX Cluster Stress Report</title>"
                + "<style>" + CSS + "</style>"
                + "<script>" + chartJs + "</script>"
                + "</head><body>"
                + "<h1>AuthX Cluster Stress Test Report</h1>"
                + "<div id=\"root\"></div>"
                + "<script>const DATA = " + json + ";\n" + RENDER_JS + "\nrenderReport(DATA);</script>"
                + "</body></html>";
    }

    private static final String CSS = """
            body{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;margin:24px;color:#222;line-height:1.5}
            h1{border-bottom:2px solid #333;padding-bottom:8px}
            h2{margin-top:32px;color:#0a5;border-bottom:1px solid #ddd;padding-bottom:4px}
            h3{margin-top:16px;color:#444}
            table{border-collapse:collapse;margin:8px 0;background:#fff}
            td,th{border:1px solid #ccc;padding:6px 12px;text-align:left}
            th{background:#f4f4f4}
            .pass{background:#d4f4dd}
            .fail{background:#fbd}
            .skipped{background:#ffe}
            canvas{max-width:900px;margin:16px 0}
            pre{background:#f4f4f4;padding:8px;overflow:auto;font-size:12px;border-radius:4px}
            .summary-card{display:inline-block;padding:12px 24px;margin:8px 8px 8px 0;background:#f8f8f8;border:1px solid #ddd;border-radius:6px}
            .summary-card .num{font-size:24px;font-weight:bold;color:#0a5}
            .summary-card .label{font-size:12px;color:#666;text-transform:uppercase}
            """;

    private static final String RENDER_JS = """
            function renderReport(d){
              const r=document.getElementById('root');
              r.innerHTML += renderEnv(d.env);
              r.innerHTML += renderSummary(d);
              r.innerHTML += renderCorrectness(d.correctness);
              r.innerHTML += renderBaseline(d.baseline);
              r.innerHTML += renderResilience(d.resilience);
              r.innerHTML += renderStress(d.stress);
              r.innerHTML += renderSoak(d.soak);
              drawCharts(d);
            }
            function renderEnv(e){
              if(!e) return '';
              return '<h2>Environment</h2><pre>'+JSON.stringify(e,null,2)+'</pre>';
            }
            function renderSummary(d){
              const c=d.correctness||{}; const r=d.resilience||{};
              const cTotal=c.total||0, cPass=c.passed||0;
              const rEntries=r?Object.values(r):[];
              const rTotal=rEntries.length;
              const rPass=rEntries.filter(x=>x&&x.status==='PASS').length;
              return '<h2>Executive Summary</h2>'
                + '<div class="summary-card"><div class="num">'+cPass+'/'+cTotal+'</div><div class="label">Correctness PASS</div></div>'
                + '<div class="summary-card"><div class="num">'+rPass+'/'+rTotal+'</div><div class="label">Resilience PASS</div></div>'
                + '<div class="summary-card"><div class="num">'+(d.baseline?Object.keys(d.baseline).length:0)+'</div><div class="label">Baselines run</div></div>';
            }
            function renderCorrectness(c){
              if(!c||!c.results) return '';
              return '<h2>Correctness (C1-C8)</h2><table><tr><th>Test</th><th>Status</th><th>Duration ms</th><th>Details</th></tr>'
                + c.results.map(x=>'<tr class="'+(x.status==='PASS'?'pass':'fail')+'"><td>'+esc(x.name)+'</td><td>'+esc(x.status)+'</td><td>'+x.durationMs+'</td><td>'+esc(x.details||'')+'</td></tr>').join('')
                + '</table>';
            }
            function renderBaseline(b){
              if(!b||Object.keys(b).length===0) return '';
              return '<h2>Baseline (B1-B5)</h2><table><tr><th>Scenario</th><th>Threads</th><th>TPS</th><th>p50 us</th><th>p99 us</th><th>p999 us</th><th>Errors</th></tr>'
                + Object.entries(b).map(([k,v])=>'<tr><td>'+esc(k)+'</td><td>'+v.threads+'</td><td>'+v.tps.toFixed(1)+'</td><td>'+v.p50us+'</td><td>'+v.p99us+'</td><td>'+v.p999us+'</td><td>'+v.errors+'</td></tr>').join('')
                + '</table><canvas id="baselineChart"></canvas>';
            }
            function renderResilience(r){
              if(!r||Object.keys(r).length===0) return '';
              return '<h2>Resilience (R1-R7)</h2><table><tr><th>ID</th><th>Status</th><th>Description</th><th>Observed</th><th>Reason</th></tr>'
                + Object.entries(r).map(([k,v])=>{
                  const cls = v.status==='PASS'?'pass':(v.status==='SKIPPED'?'skipped':'fail');
                  return '<tr class="'+cls+'"><td>'+esc(k)+'</td><td>'+esc(v.status)+'</td><td>'+esc(v.description||'')+'</td><td><pre>'+esc(JSON.stringify(v.observed,null,2))+'</pre></td><td>'+esc(v.reason||'')+'</td></tr>';
                }).join('')
                + '</table>';
            }
            function renderStress(s){
              if(!s) return '';
              return '<h2>Stress (S1/S2)</h2><pre>'+esc(JSON.stringify(s,null,2))+'</pre><canvas id="stressChart"></canvas>';
            }
            function renderSoak(s){
              if(!s) return '';
              return '<h2>Soak (L1)</h2><div>Samples: '+(s.samples?s.samples.length:0)+'</div><canvas id="soakChart"></canvas>';
            }
            function drawCharts(d){
              if(d.baseline && Object.keys(d.baseline).length>0){
                const ctx=document.getElementById('baselineChart');
                if(ctx) new Chart(ctx,{type:'bar',
                  data:{labels:Object.keys(d.baseline),
                        datasets:[
                          {label:'TPS',data:Object.values(d.baseline).map(v=>v.tps),backgroundColor:'rgba(40,167,69,0.6)'},
                          {label:'p99 us',data:Object.values(d.baseline).map(v=>v.p99us),backgroundColor:'rgba(220,53,69,0.6)',yAxisID:'y2'}]},
                  options:{scales:{y:{beginAtZero:true,title:{display:true,text:'TPS'}},y2:{beginAtZero:true,position:'right',title:{display:true,text:'p99 us'}}}}});
              }
              if(d.stress && d.stress.S1 && d.stress.S1.length){
                const ctx=document.getElementById('stressChart');
                if(ctx) new Chart(ctx,{type:'line',
                  data:{labels:d.stress.S1.map(r=>r.threads),
                        datasets:[
                          {label:'TPS',data:d.stress.S1.map(r=>r.tps),borderColor:'#28a745'},
                          {label:'p99 us',data:d.stress.S1.map(r=>r.p99us),borderColor:'#dc3545',yAxisID:'y2'}]},
                  options:{scales:{y:{beginAtZero:true},y2:{beginAtZero:true,position:'right'}}}});
              }
              if(d.soak && d.soak.samples && d.soak.samples.length){
                const ctx=document.getElementById('soakChart');
                if(ctx) new Chart(ctx,{type:'line',
                  data:{labels:d.soak.samples.map(s=>s.tsSec),
                        datasets:[
                          {label:'Heap MB',data:d.soak.samples.map(s=>s.heapMB),borderColor:'#0066cc'},
                          {label:'Threads',data:d.soak.samples.map(s=>s.threads),borderColor:'#ff8800',yAxisID:'y2'}]},
                  options:{scales:{y:{beginAtZero:true,title:{display:true,text:'Heap MB'}},y2:{beginAtZero:true,position:'right',title:{display:true,text:'Threads'}}}}});
              }
            }
            function esc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
            """;
}
