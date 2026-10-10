module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  testMatch: ['<rootDir>/test/**/*.test.ts'],
  testPathIgnorePatterns: ['/node_modules/', '/cdk.out/', '/cdk.staging/'],
  modulePathIgnorePatterns: ['<rootDir>/cdk.out/', '<rootDir>/cdk.staging/'],
};
