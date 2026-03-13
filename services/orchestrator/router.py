"""Route definitions — re-exports from main.py.

All routes are defined directly on the FastAPI app in main.py for v1.
This module exists for future refactoring into APIRouter-based organization.

To split routes in v2:
    from fastapi import APIRouter
    chat_router = APIRouter(prefix="/api", tags=["chat"])
    memory_router = APIRouter(prefix="/api/memory", tags=["memory"])
    # Then move endpoint handlers from main.py here
"""
