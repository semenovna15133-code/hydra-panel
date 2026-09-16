"""CLI entry point for Hydra Panel."""
import argparse
import os


def main():
    """Main CLI entry point."""
    parser = argparse.ArgumentParser(
        description="Hydra Control Panel",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Local development
  python hydra/cli.py --port 8000 --db-path ./panel.db

  # Production (default paths)
  python hydra/cli.py --port 443

  # Custom SSH key location
  python hydra/cli.py --ssh-key /opt/hydra/keys/panel_key
        """
    )
    
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8000, help="Port to bind (default: 8000)")
    parser.add_argument("--reload", action="store_true", help="Enable auto-reload for development")
    parser.add_argument("--app-dir", default=".", help="Application directory (default: current)")
    
    # Hydra-specific options
    parser.add_argument("--db-path", default=None, 
                       help="SQLite database path (default: /var/lib/hydra/panel.db or $HYDRA_DB_PATH)")
    parser.add_argument("--ssh-key", default=None,
                       help="SSH key path (default: /opt/hydra/keys/panel_key or $HYDRA_SSH_KEY)")
    parser.add_argument("--workers", type=int, default=1,
                       help="Number of uvicorn workers (default: 1, single-process)")
    
    args = parser.parse_args()
    
    # Set environment variables from CLI args (если не заданы уже)
    if args.db_path:
        os.environ["HYDRA_DB_PATH"] = args.db_path
    
    if args.ssh_key:
        os.environ["HYDRA_SSH_KEY"] = args.ssh_key
    
    # Default для локальной разработки если нет env vars и не переданы флаги
    if "HYDRA_DB_PATH" not in os.environ and args.db_path is None:
        # По умолчанию используем локальный файл для разработки
        os.environ["HYDRA_DB_PATH"] = "./panel.db"
    
    if "HYDRA_SSH_KEY" not in os.environ and args.ssh_key is None:
        # По умолчанию ищем ключ в keys/hydra_key
        if os.path.exists("keys/hydra_key"):
            os.environ["HYDRA_SSH_KEY"] = "keys/hydra_key"
        else:
            os.environ["HYDRA_SSH_KEY"] = "/opt/hydra/keys/panel_key"
    
    import uvicorn
    uvicorn.run(
        "hydra.panel:app",
        host=args.host,
        port=args.port,
        reload=args.reload,
        app_dir=args.app_dir,
        workers=args.workers,
    )


if __name__ == "__main__":
    main()
