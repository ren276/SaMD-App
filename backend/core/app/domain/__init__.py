"""Pure clinical rules. No IO, no ORM, no HTTP.

A module lands here when it encodes a decision a regulator would want versioned separately from
the model that produced the input to it. Everything in this package must be callable from a unit
test with a plain dict and no database.
"""
