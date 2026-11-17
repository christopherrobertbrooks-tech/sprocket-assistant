# Security

## Security fix, November 17, 2026

An independent code review of 0.1.17 found one more way a key could show up in an error message. No real key is known
to have been exposed.

1. **A hand-made access code with a quote mark or backslash could show up in a go-between error.** If a model
   server's error reply repeated the go-between's access code or upstream key, and that code contained `"` or `\`,
   the go-between blanked it out of its log but could still pass it back in the error it sent to Sprocket. *Fixed:*
   the go-between now blanks every piece of an error before turning it into a reply. *Affected:* only go-betweens
   whose access code or `UPSTREAM_KEY_FILE` key you typed yourself with one of those characters; codes made by
   `start.py` and keys from providers don't contain them. *What to do:* update the go-between. If you made your own
   code with a quote mark or backslash, make a new one.

## Security fixes, November 15, 2026

An independent code review of the new cloud brains (DeepSeek, OpenAI), done in three passes, found problems before
any of them reached a release. None is known to have exposed a real key. Every fix comes with a test that fails
without it.

1. **A key could go to the wrong company while switching brains.** If something asked Sprocket a question in the split
   second while you switched brains in Settings, that question could pair the old company's address with the new
   company's key. *Fixed:* Sprocket reads which brain is chosen once and takes the address and key from that same
   reading; every switch happens in one step. *Affected:* only builds made from the repository today; no released app.
2. **The go-between could hand a key to another server.** With its new `UPSTREAM_KEY_FILE` setting, it followed a
   model server's redirect and took the key along, even to an unencrypted address; and a proxy set on the computer
   could see a key meant for a model on that same computer. *Fixed:* it never follows redirects, sends a key only over
   https or directly to the same computer, and never through a proxy for the same computer. *Affected:* anyone who
   used `UPSTREAM_KEY_FILE` from today's repository. *What to do:* update the go-between. If it ever pointed at a
   server you don't fully trust, replace that key at the provider.
3. **A key could show up in an error message.** If a provider's error reply repeated the key it was sent, Sprocket
   could show it in a setup or chat error or a notification, and the go-between could log it or pass it back.
   *Fixed:* Sprocket and the go-between blank keys and access codes out of every error and log line: whole, in
   pieces, short ones, and a key that was replaced while a request was still running. *Affected:* only builds and
   go-betweens from today's repository.
4. **OpenAI costs could read low** when OpenAI charges for caching. *Fixed:* cache charges are counted at their own
   prices. (Not a security issue.)
