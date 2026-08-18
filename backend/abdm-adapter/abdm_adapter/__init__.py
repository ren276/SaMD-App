"""ABDM V3 adapter. Sibling package to backend/core, imported by it and mounted as a router into
the same FastAPI process (docs/backend/api-contract.md section 8, backend-prd.md section 4.3).

Top-level package name is `abdm_adapter`, not `app`, deliberately: this package is installed into
the same virtualenv as backend/core, whose own top-level package is `app`. Two distributions both
claiming the name `app` in one venv would collide on import.
"""
