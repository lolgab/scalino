/** Greets by name, used from Main to exercise cross-file references too. */
class Greeter(name: String):
  def greet(): String = s"Hello, $name!"
