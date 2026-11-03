$ErrorActionPreference = 'Stop'
$walletBase = 'http://localhost:8081'
$kycBase = 'http://localhost:8082'

function Assert-Equal($actual, $expected, $message) {
    if ($actual -ne $expected) {
        throw "$message Expected=[$expected], Actual=[$actual]"
    }
}

Write-Host '1/5 Checking application health...' -ForegroundColor Cyan
$walletHealth = Invoke-RestMethod -Uri "$walletBase/actuator/health"
$kycHealth = Invoke-RestMethod -Uri "$kycBase/actuator/health"
Assert-Equal $walletHealth.status 'UP' 'Wallet Service is not UP.'
Assert-Equal $kycHealth.status 'UP' 'KYC & Statement Service is not UP.'

$suffix = [guid]::NewGuid().ToString('N')

Write-Host '2/5 Creating two demo users...' -ForegroundColor Cyan
$senderBody = @{
    fullName = 'Smoke Test Sender'
    email = "sender-$suffix@example.test"
} | ConvertTo-Json
$receiverBody = @{
    fullName = 'Smoke Test Receiver'
    email = "receiver-$suffix@example.test"
} | ConvertTo-Json
$sender = Invoke-RestMethod -Method Post -Uri "$walletBase/api/users" -ContentType 'application/json' -Body $senderBody
$receiver = Invoke-RestMethod -Method Post -Uri "$walletBase/api/users" -ContentType 'application/json' -Body $receiverBody
Write-Host "Sender userId=$($sender.userId), walletId=$($sender.walletId)"
Write-Host "Receiver userId=$($receiver.userId), walletId=$($receiver.walletId)"

Write-Host '3/5 Adding simulated funds...' -ForegroundColor Cyan
$fundHeaders = @{
    'X-User-Id' = [string]$sender.userId
    'Idempotency-Key' = "fund-$suffix"
}
$fundBody = @{ amount = 1000.00 } | ConvertTo-Json
$null = Invoke-RestMethod -Method Post -Uri "$walletBase/api/wallets/$($sender.walletId)/add-money" -Headers $fundHeaders -ContentType 'application/json' -Body $fundBody

Write-Host '4/5 Transferring INR 125.50 and retrying with the same idempotency key...' -ForegroundColor Cyan
$transferHeaders = @{
    'X-User-Id' = [string]$sender.userId
    'Idempotency-Key' = "transfer-$suffix"
}
$transferBody = @{
    fromWalletId = $sender.walletId
    toWalletId = $receiver.walletId
    amount = 125.50
} | ConvertTo-Json
$firstTransfer = Invoke-RestMethod -Method Post -Uri "$walletBase/api/transfers" -Headers $transferHeaders -ContentType 'application/json' -Body $transferBody
$retryTransfer = Invoke-RestMethod -Method Post -Uri "$walletBase/api/transfers" -Headers $transferHeaders -ContentType 'application/json' -Body $transferBody
Assert-Equal $firstTransfer.transactionId $retryTransfer.transactionId 'Idempotent retry created a different transaction.'

Write-Host '5/5 Verifying balances...' -ForegroundColor Cyan
$senderBalance = Invoke-RestMethod -Uri "$walletBase/api/wallets/$($sender.walletId)/balance" -Headers @{ 'X-User-Id' = [string]$sender.userId }
$receiverBalance = Invoke-RestMethod -Uri "$walletBase/api/wallets/$($receiver.walletId)/balance" -Headers @{ 'X-User-Id' = [string]$receiver.userId }
Assert-Equal ([decimal]$senderBalance.balance) ([decimal]874.50) 'Unexpected sender balance.'
Assert-Equal ([decimal]$receiverBalance.balance) ([decimal]125.50) 'Unexpected receiver balance.'

Write-Host ''
Write-Host 'SMOKE TEST PASSED' -ForegroundColor Green
Write-Host "Transaction ID: $($firstTransfer.transactionId)"
Write-Host "Sender balance: INR $($senderBalance.balance)"
Write-Host "Receiver balance: INR $($receiverBalance.balance)"
Write-Host 'The repeated transfer request did not move money twice.' -ForegroundColor Green
