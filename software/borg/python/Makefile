.PHONY: all clean

all:
	@mkdir -p build
	@cd build && cmake .. -DCMAKE_CXX_COMPILER=g++ -DPython_EXECUTABLE=$(shell which python3) > /dev/null && $(MAKE) -s

clean:
	rm -rf build
