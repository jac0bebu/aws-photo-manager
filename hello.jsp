<%@ page contentType="text/html; charset=UTF-8" language="java"
         import="java.time.*,java.time.format.DateTimeFormatter,java.util.*" %>
<%
    // --- Time ---
    ZonedDateTime now = ZonedDateTime.now();
    String niceTime = now.format(DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy HH:mm:ss z"));

    // --- Session visit counter ---
    Integer visits = (Integer) session.getAttribute("visits");
    if (visits == null) visits = 0;
    visits++;
    session.setAttribute("visits", visits);

    // --- Simple name handling (stored in session) ---
    request.setCharacterEncoding("UTF-8");
    String nameParam = request.getParameter("name");
    if (nameParam != null && !nameParam.isBlank()) {
        session.setAttribute("displayName", nameParam.trim());
    }
    String displayName = (String) session.getAttribute("displayName");
    if (displayName == null || displayName.isBlank()) displayName = "Nhat dep trai";

    // --- Tiny random tip ---
    String[] tips = {
        "Tip: Reload to see the visit counter go up.",
        "Tip: Change your name below and submit.",
        "Tip: Your IP shows who’s visiting.",
        "Tip: Sessions reset when the browser/session ends."
    };
    String tip = tips[new Random().nextInt(tips.length)];

    // --- Basic request/server info ---
    String clientIP = request.getRemoteAddr();
    String server = request.getServerName() + ":" + request.getServerPort();
%>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Hello JSP — A bit more</title>
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <style>
    :root { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; }
    body { margin: 2rem; line-height: 1.6; }
    h1 { margin-bottom: .25rem; }
    .muted { color:#666; }
    .card { background:#fafafa; border:1px solid #eee; padding:1rem; border-radius:.6rem; margin:1rem 0; }
    input[type=text]{ padding:.4rem .6rem; border:1px solid #ccc; border-radius:.4rem; }
    button{ padding:.45rem .8rem; border:0; border-radius:.4rem; background:#2c7; color:#fff; cursor:pointer; }
    button:hover{ opacity:.9; }
    code { background:#f2f2f2; padding:.1rem .3rem; border-radius:.25rem; }
  </style>
</head>
<body>
  <h1>Hello from JSP 🎉 <%= displayName %></h1>
  <p class="muted"><%= tip %></p>

  <div class="card">
    <p><strong>Time:</strong> <%= niceTime %></p>
    <p><strong>Client IP:</strong> <code><%= clientIP %></code></p>
    <p><strong>Server:</strong> <code><%= server %></code></p>
    <p><strong>Session visits:</strong> <%= visits %></p>
  </div>

  <div class="card">
    <form method="post" action="">
      <label>Display name:
        <input type="text" name="name"
               value="<%= displayName.replace("\"", "&quot;") %>" />
      </label>
      <button type="submit">Update</button>
      <span class="muted"> (stored in session)</span>
    </form>
  </div>
  <img src="https://cob-bucket-nhe.s3.ap-southeast-1.amazonaws.com/Lofoten%20Islands.jpg" alt="My image">


</body>
</html>
