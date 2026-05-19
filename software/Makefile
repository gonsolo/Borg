.PHONY: all clean test borg hutt

all: borg hutt

borg:
	$(MAKE) -C borg

hutt:
	$(MAKE) -C hutt

test:
	$(MAKE) -C borg/compiler test
	$(MAKE) -C borg test-native

clean:
	$(MAKE) -C borg clean
	$(MAKE) -C hutt clean
