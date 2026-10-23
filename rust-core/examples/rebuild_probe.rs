use std::path::PathBuf;
fn main() {
    let dir = PathBuf::from(std::env::args().nth(1).expect("dir"));
    let name = std::env::args().nth(2).expect("name");
    match imgtxd_core::texdb::rebuild_cache(&dir, &name) {
        Ok(r) => println!("REBUILD OK {r}"),
        Err(e) => println!("REBUILD ERR {e}"),
    }
}
