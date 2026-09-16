"""CLI entry point for Hydra Panel."""
import argparse
import uvicorn


def main():
    """Main CLI entry point."""
    parser = argparse.ArgumentParser(description="Hydra Control Panel")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind")
    parser.add_argument("--port", type=int, default=8000, help="Port to bind")
    parser.add_argument("--reload", action="store_true", help="Enable auto-reload")
    parser.add_argument("--app-dir", default=".", help="Application directory")
    
    args = parser.parse_args()
    
    uvicorn.run(
        "hydra.panel:app",
        host=args.host,
        port=args.port,
        reload=args.reload,
        app_dir=args.app_dir,
    )


if __name__ == "__main__":
    main()
