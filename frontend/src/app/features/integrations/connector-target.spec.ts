import { maskTarget } from './connector-target';

describe('maskTarget', () => {
  it('keeps the host and the last characters of webhook URLs, which embed secrets', () => {
    expect(maskTarget('https://hooks.slack.com/services/T000/B000/abcdefgh1234')).toBe('https://hooks.slack.com/…1234');
    expect(maskTarget('https://soc.example.com/h')).toBe('https://soc.example.com/h');
  });
  it('masks email addresses and chat ids', () => {
    expect(maskTarget('soc-team@example.com')).toBe('s…@example.com');
    expect(maskTarget('-1001234567890')).toBe('-10…7890');
    expect(maskTarget('12345')).toBe('12345');
  });
});
