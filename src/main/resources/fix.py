from pathlib import Path

def main():
    carpeta_actual = Path.cwd()
    archivos_bak = list(carpeta_actual.rglob("*.bak"))

    if not archivos_bak:
        print("No se encontraron archivos .bak.")
        return

    print(f"Se encontraron {len(archivos_bak)} archivos .bak:\n")

    for archivo in archivos_bak:
        print(archivo)

    confirmar = input("\n¿Seguro que quieres eliminarlos? Escribe SI para confirmar: ")

    if confirmar != "SI":
        print("Operación cancelada.")
        return

    eliminados = 0

    for archivo in archivos_bak:
        try:
            archivo.unlink()
            eliminados += 1
        except Exception as e:
            print(f"No se pudo eliminar: {archivo} -> {e}")

    print(f"\nListo. Se eliminaron {eliminados} archivos .bak.")

if __name__ == "__main__":
    main()