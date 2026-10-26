use imgtxd_core::dff;
fn main() {
    let arg = std::env::args().nth(1).expect("dff path");
    let data = std::fs::read(&arg).unwrap();
    print!("{}", dff::inspect_dff(&data));
    let names = dff::texture_names(&data);
    if let Some(first) = names.first() {
        let renamed = dff::rename_texture(&data, first, "zz_probe_name").unwrap();
        let back = dff::rename_texture(&renamed, "zz_probe_name", first).unwrap();
        println!("rename roundtrip byte-identical: {}", back == data);
    }
}
