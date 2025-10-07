<!doctype html>
<html>
  <head><meta charset="utf-8"><title>Hello JSP</title></head>
  <body style="font-family:sans-serif">
    <h1>Hello from JSP 🎉 Nhat dep trai</h1>
    <p>Time: <%= new java.util.Date() %></p>
    <p>Client IP: <%= request.getRemoteAddr() %></p>
  </body>
</html>
