fn main() {
    let arg = std::env::args().nth(1).expect("files dir");
    match imgtxd_core::audit::audit_game_folder(std::path::Path::new(&arg)) {
        Ok(report) => print!("{report}"),
        Err(e) => println!("ERROR {e}"),
    }
    let img = std::path::Path::new(&arg).join("texdb/gta3.img");
    if img.exists() {
        println!("--- verify gta3.img ---");
        print!("{}", imgtxd_core::img::verify_archive(&img, None));
    }
}
