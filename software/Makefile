.PHONY: all clean test borg tinyqv

all: borg tinyqv

borg:
	$(MAKE) -C borg

tinyqv:
	$(MAKE) -C tinyqv

test:
	$(MAKE) -C borg/compiler test
	$(MAKE) -C borg test-native

clean:
	$(MAKE) -C borg clean
	$(MAKE) -C tinyqv clean
