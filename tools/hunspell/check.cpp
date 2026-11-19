#include <hunspell.hxx>
#include <iostream>
#include <string>
int main(int argc, char** argv) {
  Hunspell h(argv[1], argv[2]);
  std::string w;
  std::ios::sync_with_stdio(false);
  while (std::getline(std::cin, w)) {
    if (w.empty()) continue;
    std::cout << (h.spell(w) ? '1' : '0') << '\t' << w << '\n';
  }
}
