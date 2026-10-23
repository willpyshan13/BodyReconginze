CXX ?= c++
CXXFLAGS ?= -std=c++17 -Wall -Wextra -Werror -O2

.PHONY: test clean
test:
	$(CXX) $(CXXFLAGS) -Iinclude src/body_recongnize.cpp tests/body_recongnize_test.cpp -o /tmp/body_recongnize_test
	/tmp/body_recongnize_test

clean:
	rm -f /tmp/body_recongnize_test
