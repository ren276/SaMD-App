"""ASGI middleware.

Order, outermost first, is fixed in app.main:

    RequestIdMiddleware -> HttpsEnforcementMiddleware -> AuditMiddleware -> routes

Request-ID is outermost so an HTTPS rejection still carries a request_id and still logs one
line. Audit is innermost so it observes the final status after every other layer has run.
"""
